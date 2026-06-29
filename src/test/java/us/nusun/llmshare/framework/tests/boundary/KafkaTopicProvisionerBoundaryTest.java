package us.nusun.llmshare.framework.tests.boundary;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.llm.KafkaTopicProvisioner;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaTopicProvisionerBoundaryTest {
    @Test
    void configuredTopicNamesIncludeLaneRequestReplyTopicsAndDeadLetterOnce() {
        KafkaTopicProvisioner provisioner = provisioner(true);
        FrameworkFixtures.set(provisioner, "deadLetterTopic", "llm.dead-letter");

        List<String> topics = provisioner.configuredTopicNames();

        assertEquals(Set.of(
                "llm.requests.low",
                "llm.replies.low",
                "llm.requests.high",
                "llm.replies.high",
                "llm.dead-letter"), Set.copyOf(topics));
        assertEquals(5, topics.size());
        assertTrue(topics.indexOf("llm.requests.low") < topics.indexOf("llm.replies.low"));
        assertTrue(topics.indexOf("llm.requests.high") < topics.indexOf("llm.replies.high"));
        assertEquals("llm.dead-letter", topics.getLast());
    }

    @Test
    void configuredTopicNamesDeduplicateDeadLetterWhenItMatchesLaneTopic() {
        KafkaTopicProvisioner provisioner = provisioner(true);
        FrameworkFixtures.set(provisioner, "deadLetterTopic", "llm.requests.low");

        List<String> topics = provisioner.configuredTopicNames();

        assertEquals(Set.of(
                "llm.requests.low",
                "llm.replies.low",
                "llm.requests.high",
                "llm.replies.high"), Set.copyOf(topics));
        assertEquals(4, topics.size());
    }

    @Test
    void configuredTopicsApplyPartitionAndReplicationSettingsToEveryTopic() {
        KafkaTopicProvisioner provisioner = provisioner(true);
        FrameworkFixtures.set(provisioner, "partitions", 3);
        FrameworkFixtures.set(provisioner, "replicationFactor", (short) 2);

        List<NewTopic> topics = provisioner.configuredTopics();

        assertEquals(Set.copyOf(provisioner.configuredTopicNames()), Set.copyOf(topics.stream().map(NewTopic::name).toList()));
        assertTrue(topics.stream().allMatch(topic -> topic.numPartitions() == 3));
        assertTrue(topics.stream().allMatch(topic -> topic.replicationFactor() == 2));
    }

    @Test
    void ensureTopicsReturnsConfiguredNamesWithoutBootstrapWhenProvisioningDisabled() throws Exception {
        KafkaTopicProvisioner provisioner = provisioner(false);
        FrameworkFixtures.set(provisioner, "bootstrapServers", null);

        assertEquals(provisioner.configuredTopicNames(), provisioner.ensureTopics());
    }

    @Test
    void ensureTopicsFailsClearlyWhenProvisioningEnabledButBootstrapIsMissing() {
        KafkaTopicProvisioner provisioner = provisioner(true);
        FrameworkFixtures.set(provisioner, "bootstrapServers", null);

        IllegalStateException failure = assertThrows(IllegalStateException.class, provisioner::ensureTopics);

        assertEquals("kafka.bootstrap.servers is required when llm.kafka.provision-topics=true", failure.getMessage());
    }

    @Test
    void ensureTopicsFailsClearlyWhenProvisioningEnabledButBootstrapIsBlank() {
        KafkaTopicProvisioner provisioner = provisioner(true);
        FrameworkFixtures.set(provisioner, "bootstrapServers", "   ");

        IllegalStateException failure = assertThrows(IllegalStateException.class, provisioner::ensureTopics);

        assertEquals("kafka.bootstrap.servers is required when llm.kafka.provision-topics=true", failure.getMessage());
    }

    @Test
    void ensureTopicsCreatesOnlyTopicsMissingFromKafka() throws Exception {
        TestableProvisioner provisioner = provisionerWithExistingTopics(Set.of(
                "llm.requests.low",
                "llm.replies.low",
                "llm.dead-letter"));

        List<String> ensured = provisioner.ensureTopics();

        assertEquals(provisioner.configuredTopicNames(), ensured);
        assertEquals(Set.of("llm.requests.high", "llm.replies.high"), Set.copyOf(provisioner.createdTopicNames()));
        assertEquals(2, provisioner.createdTopicNames().size());
        assertEquals(1, provisioner.closeCount);
    }

    @Test
    void ensureTopicsDoesNotCreateTopicsWhenAllConfiguredTopicsAlreadyExist() throws Exception {
        TestableProvisioner provisioner = provisionerWithExistingTopics(Set.copyOf(provisioner(true).configuredTopicNames()));

        List<String> ensured = provisioner.ensureTopics();

        assertEquals(provisioner.configuredTopicNames(), ensured);
        assertEquals(List.of(), provisioner.createdTopicNames());
        assertEquals(1, provisioner.closeCount);
    }

    @Test
    void ensureTopicsIgnoresTopicExistsRaceDuringCreate() throws Exception {
        TestableProvisioner provisioner = provisionerWithExistingTopics(Set.of());
        provisioner.createFailure = new ExecutionException(new TopicExistsException("topic already exists"));

        List<String> ensured = provisioner.ensureTopics();

        assertEquals(provisioner.configuredTopicNames(), ensured);
        assertEquals(Set.copyOf(provisioner.configuredTopicNames()), Set.copyOf(provisioner.createdTopicNames()));
        assertEquals(provisioner.configuredTopicNames().size(), provisioner.createdTopicNames().size());
        assertEquals(1, provisioner.closeCount);
    }

    @Test
    void ensureTopicsPropagatesNonTopicExistsCreateFailures() {
        TestableProvisioner provisioner = provisionerWithExistingTopics(Set.of());
        provisioner.createFailure = new ExecutionException(new IllegalStateException("broker unavailable"));

        ExecutionException failure = assertThrows(ExecutionException.class, provisioner::ensureTopics);

        assertEquals("broker unavailable", failure.getCause().getMessage());
        assertEquals(Set.copyOf(provisioner.configuredTopicNames()), Set.copyOf(provisioner.createdTopicNames()));
        assertEquals(provisioner.configuredTopicNames().size(), provisioner.createdTopicNames().size());
        assertEquals(1, provisioner.closeCount);
    }

    private static KafkaTopicProvisioner provisioner(boolean enabled) {
        KafkaTopicProvisioner provisioner = new KafkaTopicProvisioner();
        FrameworkFixtures.set(provisioner, "runtimes", FrameworkFixtures.executionRuntimeRegistry());
        FrameworkFixtures.set(provisioner, "deadLetterTopic", "llm.dead-letter");
        FrameworkFixtures.set(provisioner, "bootstrapServers", "localhost:9092");
        FrameworkFixtures.set(provisioner, "provisionTopics", enabled);
        FrameworkFixtures.set(provisioner, "partitions", 1);
        FrameworkFixtures.set(provisioner, "replicationFactor", (short) 1);
        return provisioner;
    }

    private static TestableProvisioner provisionerWithExistingTopics(Set<String> existingTopics) {
        TestableProvisioner provisioner = new TestableProvisioner(existingTopics);
        FrameworkFixtures.set(provisioner, "runtimes", FrameworkFixtures.executionRuntimeRegistry());
        FrameworkFixtures.set(provisioner, "deadLetterTopic", "llm.dead-letter");
        FrameworkFixtures.set(provisioner, "bootstrapServers", "localhost:9092");
        FrameworkFixtures.set(provisioner, "provisionTopics", true);
        FrameworkFixtures.set(provisioner, "partitions", 1);
        FrameworkFixtures.set(provisioner, "replicationFactor", (short) 1);
        return provisioner;
    }

    private static class TestableProvisioner extends KafkaTopicProvisioner {
        private final Set<String> existingTopics;
        private final List<NewTopic> createdTopics = new ArrayList<>();
        private ExecutionException createFailure;
        private int closeCount;

        private TestableProvisioner(Set<String> existingTopics) {
            this.existingTopics = existingTopics;
        }

        @Override
        protected Admin adminClient() {
            return (Admin) Proxy.newProxyInstance(
                    Admin.class.getClassLoader(),
                    new Class<?>[] {Admin.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            closeCount++;
                            return null;
                        }
                        throw new UnsupportedOperationException("Unexpected Admin call: " + method.getName());
                    });
        }

        @Override
        protected Set<String> existingTopicNames(Admin adminClient) {
            return existingTopics;
        }

        @Override
        protected void createTopics(Admin adminClient, List<NewTopic> topics) throws ExecutionException {
            createdTopics.addAll(topics);
            if (createFailure != null) {
                throw createFailure;
            }
        }

        private List<String> createdTopicNames() {
            return createdTopics.stream().map(NewTopic::name).toList();
        }
    }
}
