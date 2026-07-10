package us.nusun.llmshare.framework.llm;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import us.nusun.llmshare.framework.inventory.InferenceLaneRegistry;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@ApplicationScoped
public class KafkaTopicProvisioner {
    private static final Logger LOG = Logger.getLogger(KafkaTopicProvisioner.class);

    @jakarta.inject.Inject InferenceLaneRegistry lanes;
    @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers;
    @ConfigProperty(name = "llm.topic.dead-letter", defaultValue = "llm.dead-letter") String deadLetterTopic;
    @ConfigProperty(name = "agent-run.scheduled.topic", defaultValue = "agent-runs.scheduled") String agentRunScheduledTopic;
    @ConfigProperty(name = "llm.kafka.provision-topics", defaultValue = "true") boolean provisionTopics;
    @ConfigProperty(name = "llm.kafka.topic-partitions", defaultValue = "1") int partitions;
    @ConfigProperty(name = "llm.kafka.topic-replication-factor", defaultValue = "1") short replicationFactor;

    void onStart(@Observes StartupEvent ignored) {
        if (!provisionTopics) {
            LOG.debug("Kafka topic provisioning disabled");
            return;
        }
        try {
            ensureTopics();
        } catch (Exception e) {
            LOG.warnf(e, "Failed to provision Kafka topics %s", configuredTopicNames());
        }
    }

    public List<String> configuredTopicNames() {
        java.util.ArrayList<String> topics = new java.util.ArrayList<>();
        for (InferenceLaneDefinition lane : lanes.allLanes()) {
            topics.add(lane.requestTopic());
            topics.add(lane.responseTopic());
        }
        topics.add(agentRunScheduledTopic);
        topics.add(deadLetterTopic);
        return topics.stream().distinct().toList();
    }

    public List<NewTopic> configuredTopics() {
        return configuredTopicPartitions().entrySet().stream()
                .map(topic -> new NewTopic(topic.getKey(), topic.getValue(), replicationFactor))
                .toList();
    }

    public Map<String, Integer> configuredTopicPartitions() {
        LinkedHashMap<String, Integer> topicPartitions = new LinkedHashMap<>();
        for (InferenceLaneDefinition lane : lanes.allLanes()) {
            topicPartitions.merge(lane.requestTopic(), lane.parallelism(), Math::max);
            topicPartitions.putIfAbsent(lane.responseTopic(), 1);
        }
        topicPartitions.putIfAbsent(agentRunScheduledTopic, Math.max(1, partitions));
        topicPartitions.putIfAbsent(deadLetterTopic, Math.max(1, partitions));
        return Collections.unmodifiableMap(topicPartitions);
    }

    public List<String> ensureTopics() throws Exception {
        if (!provisionTopics) {
            return configuredTopicNames();
        }
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException("kafka.bootstrap.servers is required when llm.kafka.provision-topics=true");
        }
        try (Admin adminClient = adminClient()) {
            Set<String> existingTopics = existingTopicNames(adminClient);
            verifyExistingRequestTopicPartitions(adminClient, existingTopics);
            List<NewTopic> missingTopics = configuredTopics().stream()
                    .filter(topic -> !existingTopics.contains(topic.name()))
                    .toList();
            if (!missingTopics.isEmpty()) {
                try {
                    createTopics(adminClient, missingTopics);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                        throw e;
                    }
                }
            }
            LOG.infof("Ensured Kafka topics exist: %s", configuredTopicNames());
            return configuredTopicNames();
        }
    }

    public List<String> purgeConfiguredTopics() throws Exception {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException("kafka.bootstrap.servers is required to purge Kafka topics during reset");
        }
        try (Admin adminClient = adminClient()) {
            Set<String> existingTopics = existingTopicNames(adminClient);
            List<String> purgeTopics = configuredTopicNames().stream()
                    .filter(existingTopics::contains)
                    .toList();
            if (purgeTopics.isEmpty()) {
                return List.of();
            }

            Map<String, Integer> partitionsByTopic = existingTopicPartitions(adminClient, Set.copyOf(purgeTopics));
            LinkedHashMap<TopicPartition, RecordsToDelete> recordsToDelete = new LinkedHashMap<>();
            for (String topic : purgeTopics) {
                int topicPartitions = partitionsByTopic.getOrDefault(topic, 0);
                for (int partition = 0; partition < topicPartitions; partition++) {
                    recordsToDelete.put(new TopicPartition(topic, partition), RecordsToDelete.beforeOffset(-1));
                }
            }
            if (!recordsToDelete.isEmpty()) {
                deleteRecords(adminClient, recordsToDelete);
            }
            LOG.infof("Purged Kafka topics during reset: %s", purgeTopics);
            return purgeTopics;
        }
    }

    protected Set<String> existingTopicNames(Admin adminClient) throws Exception {
        return adminClient.listTopics().names().get();
    }

    protected Map<String, Integer> existingTopicPartitions(Admin adminClient, Set<String> topicNames) throws Exception {
        if (topicNames.isEmpty()) {
            return Map.of();
        }
        Map<String, TopicDescription> descriptions = adminClient.describeTopics(topicNames).allTopicNames().get();
        LinkedHashMap<String, Integer> partitionsByTopic = new LinkedHashMap<>();
        descriptions.forEach((topic, description) -> partitionsByTopic.put(topic, description.partitions().size()));
        return partitionsByTopic;
    }

    protected void createTopics(Admin adminClient, List<NewTopic> topics) throws Exception {
        adminClient.createTopics(topics).all().get();
    }

    protected void deleteRecords(Admin adminClient, Map<TopicPartition, RecordsToDelete> recordsToDelete) throws Exception {
        adminClient.deleteRecords(recordsToDelete).all().get();
    }

    protected Admin adminClient() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    private void verifyExistingRequestTopicPartitions(Admin adminClient, Set<String> existingTopics) throws Exception {
        LinkedHashMap<String, Integer> requiredRequestPartitions = new LinkedHashMap<>();
        for (InferenceLaneDefinition lane : lanes.allLanes()) {
            if (existingTopics.contains(lane.requestTopic())) {
                requiredRequestPartitions.merge(lane.requestTopic(), lane.parallelism(), Math::max);
            }
        }
        if (requiredRequestPartitions.isEmpty()) {
            return;
        }
        Map<String, Integer> actualPartitions = existingTopicPartitions(adminClient, requiredRequestPartitions.keySet());
        for (Map.Entry<String, Integer> required : requiredRequestPartitions.entrySet()) {
            int actual = actualPartitions.getOrDefault(required.getKey(), 0);
            if (actual < required.getValue()) {
                throw new IllegalStateException("Kafka topic " + required.getKey()
                        + " has " + actual
                        + " partitions but lane parallelism requires at least "
                        + required.getValue()
                        + ". Topic provisioning creates missing topics but does not increase partitions on existing topics; increase the topic partition count or reduce the lane parallelism.");
            }
        }
    }
}
