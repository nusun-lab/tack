package us.nusun.tack.framework.tests.boundary;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inventory.InferenceLaneRegistry;
import us.nusun.tack.framework.llm.KafkaTopicProvisioner;
import us.nusun.tack.framework.model.InferenceLaneDefinition;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                "test.llm.requests.low",
                "test.llm.replies.low",
                "test.llm.requests.high",
                "test.llm.replies.high",
                "agent-runs.scheduled",
                "llm.dead-letter"), Set.copyOf(topics));
        assertEquals(6, topics.size());
        assertTrue(topics.indexOf("test.llm.requests.low") < topics.indexOf("test.llm.replies.low"));
        assertTrue(topics.indexOf("test.llm.requests.high") < topics.indexOf("test.llm.replies.high"));
        assertEquals("agent-runs.scheduled", topics.get(topics.size() - 2));
        assertEquals("llm.dead-letter", topics.getLast());
    }

    @Test
    void configuredTopicNamesDeduplicateDeadLetterWhenItMatchesLaneTopic() {
        KafkaTopicProvisioner provisioner = provisioner(true);
        FrameworkFixtures.set(provisioner, "deadLetterTopic", "test.llm.requests.low");

        List<String> topics = provisioner.configuredTopicNames();

        assertEquals(Set.of(
                "test.llm.requests.low",
                "test.llm.replies.low",
                "test.llm.requests.high",
                "test.llm.replies.high",
                "agent-runs.scheduled"), Set.copyOf(topics));
        assertEquals(5, topics.size());
    }

    @Test
    void configuredTopicsUseLaneParallelismForRequestsAndSinglePartitionReplies() {
        KafkaTopicProvisioner provisioner = provisioner(true, registryWithLowLaneParallelism(3));
        FrameworkFixtures.set(provisioner, "partitions", 4);
        FrameworkFixtures.set(provisioner, "replicationFactor", (short) 2);

        List<NewTopic> topics = provisioner.configuredTopics();
        Map<String, Integer> partitionsByTopic = topics.stream()
                .collect(java.util.stream.Collectors.toMap(NewTopic::name, NewTopic::numPartitions));

        assertEquals(Set.copyOf(provisioner.configuredTopicNames()), Set.copyOf(topics.stream().map(NewTopic::name).toList()));
        assertEquals(3, partitionsByTopic.get("test.llm.requests.low"));
        assertEquals(1, partitionsByTopic.get("test.llm.replies.low"));
        assertEquals(1, partitionsByTopic.get("test.llm.requests.high"));
        assertEquals(1, partitionsByTopic.get("test.llm.replies.high"));
        assertEquals(4, partitionsByTopic.get("agent-runs.scheduled"));
        assertEquals(4, partitionsByTopic.get("llm.dead-letter"));
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
                "test.llm.requests.low",
                "test.llm.replies.low",
                "llm.dead-letter"));

        List<String> ensured = provisioner.ensureTopics();

        assertEquals(provisioner.configuredTopicNames(), ensured);
        assertEquals(Set.of("test.llm.requests.high", "test.llm.replies.high", "agent-runs.scheduled"), Set.copyOf(provisioner.createdTopicNames()));
        assertEquals(3, provisioner.createdTopicNames().size());
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
    void ensureTopicsFailsClearlyWhenExistingRequestTopicHasTooFewPartitions() {
        TestableProvisioner provisioner = provisionerWithExistingTopics(
                Set.of("test.llm.requests.low"),
                registryWithLowLaneParallelism(3));
        provisioner.existingPartitions = Map.of("test.llm.requests.low", 1);

        IllegalStateException failure = assertThrows(IllegalStateException.class, provisioner::ensureTopics);

        assertTrue(failure.getMessage().contains("test.llm.requests.low"));
        assertTrue(failure.getMessage().contains("has 1 partitions"));
        assertTrue(failure.getMessage().contains("requires at least 3"));
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

    @Test
    void purgeConfiguredTopicsDeletesRecordsToHighWatermarkForExistingConfiguredPartitions() throws Exception {
        TestableProvisioner provisioner = provisionerWithExistingTopics(Set.of(
                "test.llm.requests.low",
                "test.llm.replies.high",
                "agent-runs.scheduled",
                "not-configured"));
        provisioner.existingPartitions = Map.of(
                "test.llm.requests.low", 2,
                "test.llm.replies.high", 1,
                "agent-runs.scheduled", 3,
                "not-configured", 5);

        List<String> purged = provisioner.purgeConfiguredTopics();

        assertEquals(Set.of("test.llm.requests.low", "test.llm.replies.high", "agent-runs.scheduled"), Set.copyOf(purged));
        assertEquals(6, provisioner.deletedRecords.size());
        assertEquals(RecordsToDelete.beforeOffset(-1), provisioner.deletedRecords.get(new TopicPartition("test.llm.requests.low", 0)));
        assertEquals(RecordsToDelete.beforeOffset(-1), provisioner.deletedRecords.get(new TopicPartition("test.llm.requests.low", 1)));
        assertEquals(RecordsToDelete.beforeOffset(-1), provisioner.deletedRecords.get(new TopicPartition("test.llm.replies.high", 0)));
        assertEquals(RecordsToDelete.beforeOffset(-1), provisioner.deletedRecords.get(new TopicPartition("agent-runs.scheduled", 2)));
        assertTrue(provisioner.deletedRecords.keySet().stream().noneMatch(partition -> "not-configured".equals(partition.topic())));
        assertEquals(1, provisioner.closeCount);
    }

    @Test
    void purgeConfiguredTopicsNoopsWhenNoConfiguredTopicsExist() throws Exception {
        TestableProvisioner provisioner = provisionerWithExistingTopics(Set.of("unrelated"));

        List<String> purged = provisioner.purgeConfiguredTopics();

        assertEquals(List.of(), purged);
        assertEquals(Map.of(), provisioner.deletedRecords);
        assertEquals(1, provisioner.closeCount);
    }

    private static KafkaTopicProvisioner provisioner(boolean enabled) {
        return provisioner(enabled, FrameworkFixtures.inferenceLaneRegistry());
    }

    private static KafkaTopicProvisioner provisioner(boolean enabled, InferenceLaneRegistry registry) {
        KafkaTopicProvisioner provisioner = new KafkaTopicProvisioner();
        FrameworkFixtures.set(provisioner, "lanes", registry);
        FrameworkFixtures.set(provisioner, "deadLetterTopic", "llm.dead-letter");
        FrameworkFixtures.set(provisioner, "agentRunScheduledTopic", "agent-runs.scheduled");
        FrameworkFixtures.set(provisioner, "bootstrapServers", "localhost:9092");
        FrameworkFixtures.set(provisioner, "provisionTopics", enabled);
        FrameworkFixtures.set(provisioner, "partitions", 1);
        FrameworkFixtures.set(provisioner, "replicationFactor", (short) 1);
        return provisioner;
    }

    private static TestableProvisioner provisionerWithExistingTopics(Set<String> existingTopics) {
        return provisionerWithExistingTopics(existingTopics, FrameworkFixtures.inferenceLaneRegistry());
    }

    private static TestableProvisioner provisionerWithExistingTopics(Set<String> existingTopics, InferenceLaneRegistry registry) {
        TestableProvisioner provisioner = new TestableProvisioner(existingTopics);
        FrameworkFixtures.set(provisioner, "lanes", registry);
        FrameworkFixtures.set(provisioner, "deadLetterTopic", "llm.dead-letter");
        FrameworkFixtures.set(provisioner, "agentRunScheduledTopic", "agent-runs.scheduled");
        FrameworkFixtures.set(provisioner, "bootstrapServers", "localhost:9092");
        FrameworkFixtures.set(provisioner, "provisionTopics", true);
        FrameworkFixtures.set(provisioner, "partitions", 1);
        FrameworkFixtures.set(provisioner, "replicationFactor", (short) 1);
        return provisioner;
    }

    private static InferenceLaneRegistry registryWithLowLaneParallelism(int parallelism) {
        return new InferenceLaneRegistry(
                List.of(
                        InferenceLaneDefinition.builder("low-pass")
                                .requestTopic("test.llm.requests.low")
                                .responseTopic("test.llm.replies.low")
                                .parallelism(parallelism)
                                .build(),
                        InferenceLaneDefinition.builder("high-pass")
                                .requestTopic("test.llm.requests.high")
                                .responseTopic("test.llm.replies.high")
                                .parallelism(1)
                                .build()));
    }

    private static class TestableProvisioner extends KafkaTopicProvisioner {
        private final Set<String> existingTopics;
        private final List<NewTopic> createdTopics = new ArrayList<>();
        private final Map<TopicPartition, RecordsToDelete> deletedRecords = new java.util.LinkedHashMap<>();
        private Map<String, Integer> existingPartitions = Map.of();
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
        protected Map<String, Integer> existingTopicPartitions(Admin adminClient, Set<String> topicNames) {
            return topicNames.stream()
                    .collect(java.util.stream.Collectors.toMap(topic -> topic, topic -> existingPartitions.getOrDefault(topic, 1)));
        }

        @Override
        protected void createTopics(Admin adminClient, List<NewTopic> topics) throws ExecutionException {
            createdTopics.addAll(topics);
            if (createFailure != null) {
                throw createFailure;
            }
        }

        @Override
        protected void deleteRecords(Admin adminClient, Map<TopicPartition, RecordsToDelete> recordsToDelete) {
            deletedRecords.putAll(recordsToDelete);
        }

        private List<String> createdTopicNames() {
            return createdTopics.stream().map(NewTopic::name).toList();
        }
    }
}
