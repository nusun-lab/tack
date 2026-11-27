package us.nusun.tack.framework.inference;

import dev.langchain4j.service.Result;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.kafka.KafkaComponent;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.apache.camel.component.langchain4j.agent.api.AgentFactory;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import us.nusun.tack.framework.inference.lane.InferenceLaneDefinition;
import us.nusun.tack.framework.inference.lane.InferenceLaneRegistry;
import us.nusun.tack.framework.logging.LogTokens;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestCamelContexts;
import us.nusun.tack.framework.tests.support.TestJson;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "TACK_TEST_KAFKA_BOOTSTRAP", matches = ".+")
class LlmKafkaRebalanceTest {
    @Test
    void pollExpiryDoesNotReplayTheRunningActAndAnotherRunCanProgress() throws Exception {
        var broker = System.getenv("TACK_TEST_KAFKA_BOOTSTRAP");
        var prefix = "tack-rebalance-" + UUID.randomUUID();
        var requestTopic = prefix + "-requests";
        var replyTopic = prefix + "-replies";
        var deadLetterTopic = prefix + "-dead-letter";
        var group = prefix + ".high-pass";
        var longJob = UUID.randomUUID();
        var effectCount = new AtomicInteger();
        var firstStarted = new CountDownLatch(1);
        var nextStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var independentStarts = new AtomicInteger();
        var topics = List.of(requestTopic, replyTopic, deadLetterTopic);
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker));
             var context = TestCamelContexts.quiet();
             var callers = Executors.newFixedThreadPool(2)) {
            admin.createTopics(topics.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList())
                    .all().get(10, TimeUnit.SECONDS);
            context.setAutowiredEnabled(false);
            context.getShutdownStrategy().setTimeout(2);
            var kafka = new KafkaComponent();
            kafka.getConfiguration().setBrokers(broker);
            context.addComponent("kafka", kafka);
            Properties properties = new Properties();
            properties.setProperty("llm.consumer.group", prefix);
            properties.setProperty("llm.reply-timeout-ms", "30000");
            properties.setProperty("llm.topic.dead-letter", deadLetterTopic);
            context.getPropertiesComponent().setOverrideProperties(properties);

            InFlightReplyManager replies = new InFlightReplyManager();
            var deadLetters = new LlmDeadLetterFactory();
            var route = new LlmRoute();
            var lanes = new InferenceLaneRegistry(List.of(
                    InferenceLaneDefinition.builder("high-pass").requestTopic(requestTopic)
                            .responseTopic(replyTopic).parallelism(2).build()));
            var jobs = FrameworkFixtures.jobFactory();
            FrameworkFixtures.set(jobs, "lanes", lanes);
            FrameworkFixtures.set(route, "lanes", lanes);
            FrameworkFixtures.set(route, "jobFactory", jobs);
            FrameworkFixtures.set(route, "deadLetterFactory", deadLetters);
            FrameworkFixtures.set(route, "routeSupport", FrameworkFixtures.llmRouteSupport(replies, deadLetters, new LogTokens(false)));
            context.getRegistry().bind("dynamicAgentFactory", agentFactory(longJob, effectCount, independentStarts,
                    firstStarted, nextStarted, release));
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(route);

            String worker = context.getRouteDefinition("llm-worker-high-pass").getInput().getUri();
            assertTrue(worker.contains("maxPollIntervalMs=900000"), worker);
            AdviceWith.adviceWith(context, "llm-worker-high-pass", advice -> advice.replaceFromWith(
                    worker.replace("maxPollIntervalMs=900000", "maxPollIntervalMs=1200")
                            + "&sessionTimeoutMs=6000&heartbeatIntervalMs=200&pollTimeoutMs=100"));
            context.start();
            try (var producer = context.createProducerTemplate()) {
                awaitMembers(admin, group, 2);
                var original = callers.submit(() -> producer.requestBody("direct:llm",
                        request(longJob, "repro-original"), LlmJobResult.class));
                try {
                    assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "original Act did not start");
                    var replacement = assertThrows(CamelExecutionException.class, () -> producer.requestBody(
                            "direct:llm", request(UUID.randomUUID(), "repro-original"), LlmJobResult.class));
                    assertTrue(replacement.getCause().getMessage().contains("already has an in-flight LLM job"));
                    producer.sendBody("kafka:" + requestTopic + "?synchronous=true",
                            TestJson.write(FrameworkFixtures.queuedJob(request(longJob, "repro-original"))));
                    var independent = callers.submit(() -> producer.requestBody("direct:llm",
                            request(UUID.randomUUID(), "repro-independent"), LlmJobResult.class));
                    assertTrue(nextStarted.await(12, TimeUnit.SECONDS), "standby consumer did not take over the partition");
                    assertEquals(1, effectCount.get(), "the replacement consumer reran the blocked Act's side effect");
                    assertEquals(1, independentStarts.get(), "independent work should start while the original is blocked");
                    assertEquals(LlmJobStatus.SUCCEEDED, independent.get(10, TimeUnit.SECONDS).status());
                    assertFalse(original.isDone(), "the original should still be executing");
                } finally {
                    release.countDown();
                }
                assertEquals(LlmJobStatus.SUCCEEDED, original.get(10, TimeUnit.SECONDS).status());
                assertEquals(3L, admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).values().iterator().next().offset());
            } finally {
                release.countDown();
                context.stop();
                admin.deleteTopics(topics).all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void awaitMembers(Admin admin, String group, int size) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var groups = admin.describeConsumerGroups(List.of(group)).all().get(5, TimeUnit.SECONDS);
                if (groups.get(group).members().size() == size) {
                    return;
                }
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof GroupIdNotFoundException)) throw failure;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Kafka worker group did not acquire its members: " + group);
    }

    private static LlmJobEnvelope request(UUID id, String run) {
        return new LlmJobEnvelope(id, "high-pass", LlmPassType.ACTION, "test", "test", 0,
                "Simulated tool operation", null, null,
                Map.of("agentRunId", run, "primitive", "act", "primitiveId", "repro-act"), null);
    }

    private static AgentFactory agentFactory(UUID longJob, AtomicInteger effects, AtomicInteger independent,
                                             CountDownLatch firstStarted, CountDownLatch nextStarted, CountDownLatch release) {
        return new AgentFactory() {
            private CamelContext context;

            @Override
            public Agent createAgent(Exchange exchange) {
                boolean original = longJob.equals(LlmExecutionContext.require(exchange).jobId());
                return (body, tools) -> {
                    if (original) {
                        int attempt = effects.incrementAndGet();
                        (attempt == 1 ? firstStarted : nextStarted).countDown();
                        try {
                            if (!release.await(25, TimeUnit.SECONDS)) throw new AssertionError("original Act was not released");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                    } else {
                        independent.incrementAndGet();
                        nextStarted.countDown();
                    }
                    return Result.<String>builder().content("done").build();
                };
            }

            @Override
            public CamelContext getCamelContext() {
                return context;
            }

            @Override
            public void setCamelContext(CamelContext context) {
                this.context = context;
            }
        };
    }
}
