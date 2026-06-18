package us.nusun.llmshare.bean;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@ApplicationScoped
public class KafkaTopicProvisioner {
    private static final Logger LOG = Logger.getLogger(KafkaTopicProvisioner.class);

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @ConfigProperty(name = "llm.topic.requests")
    String requestsTopic;

    @ConfigProperty(name = "llm.topic.responses")
    String responsesTopic;

    @ConfigProperty(name = "llm.topic.dead-letter")
    String deadLetterTopic;

    @ConfigProperty(name = "llm.kafka.provision-topics", defaultValue = "true")
    boolean provisionTopics;

    @ConfigProperty(name = "llm.kafka.topic-partitions", defaultValue = "1")
    int partitions;

    @ConfigProperty(name = "llm.kafka.topic-replication-factor", defaultValue = "1")
    short replicationFactor;

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
        return List.of(requestsTopic, responsesTopic, deadLetterTopic);
    }

    public List<NewTopic> configuredTopics() {
        return configuredTopicNames().stream()
                .map(topic -> new NewTopic(topic, partitions, replicationFactor))
                .toList();
    }

    public List<String> ensureTopics() throws Exception {
        if (!provisionTopics) {
            return configuredTopicNames();
        }

        try (AdminClient adminClient = adminClient()) {
            Set<String> existingTopics = new HashSet<>(adminClient.listTopics().names().get());
            List<NewTopic> missingTopics = configuredTopics().stream()
                    .filter(topic -> !existingTopics.contains(topic.name()))
                    .toList();
            if (!missingTopics.isEmpty()) {
                try {
                    adminClient.createTopics(missingTopics).all().get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                    LOG.debugf("Kafka topics were created concurrently while provisioning: %s", configuredTopicNames());
                }
            }
            LOG.infof("Ensured Kafka topics exist: %s", configuredTopicNames());
            return configuredTopicNames();
        }
    }

    public List<String> resetTopics() throws Exception {
        if (!provisionTopics) {
            return configuredTopicNames();
        }

        ensureTopics();
        try (AdminClient adminClient = adminClient(); KafkaConsumer<String, String> consumer = resetConsumer()) {
            List<TopicPartition> partitionsToReset = topicPartitions(adminClient);
            consumer.assign(partitionsToReset);
            consumer.poll(Duration.ZERO);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitionsToReset);
            Map<TopicPartition, RecordsToDelete> deletions = endOffsets.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> RecordsToDelete.beforeOffset(entry.getValue())));
            adminClient.deleteRecords(deletions).all().get();
            LOG.infof("Reset Kafka topics: %s", configuredTopicNames());
            return configuredTopicNames();
        }
    }

    public List<Map<String, Object>> topicDetails() {
        try (AdminClient adminClient = adminClient(); KafkaConsumer<String, String> consumer = resetConsumer()) {
            Map<String, Object> describedTopics = new LinkedHashMap<>(adminClient.describeTopics(configuredTopicNames()).allTopicNames().get());
            List<TopicPartition> topicPartitions = topicPartitions(adminClient);
            consumer.assign(topicPartitions);
            consumer.poll(Duration.ZERO);
            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(topicPartitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(topicPartitions);

            return configuredTopicNames().stream()
                    .map(topic -> topicDetail(topic, describedTopics, beginningOffsets, endOffsets))
                    .toList();
        } catch (Exception e) {
            return configuredTopicNames().stream()
                    .map(topic -> topicErrorDetail(topic, e))
                    .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> topicDetail(
            String topic,
            Map<String, Object> describedTopics,
            Map<TopicPartition, Long> beginningOffsets,
            Map<TopicPartition, Long> endOffsets) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", topic);
        try {
            Object description = describedTopics.get(topic);
            List<?> partitions = (List<?>) description.getClass().getMethod("partitions").invoke(description);
            row.put("partitions", partitions.size());

            List<Map<String, Object>> offsetRows = partitions.stream()
                    .map(partition -> {
                        try {
                            int partitionId = (Integer) partition.getClass().getMethod("partition").invoke(partition);
                            TopicPartition topicPartition = new TopicPartition(topic, partitionId);
                            long beginning = beginningOffsets.getOrDefault(topicPartition, 0L);
                            long end = endOffsets.getOrDefault(topicPartition, 0L);
                            Map<String, Object> offsetRow = new LinkedHashMap<>();
                            offsetRow.put("partition", partitionId);
                            offsetRow.put("beginningOffset", beginning);
                            offsetRow.put("endOffset", end);
                            offsetRow.put("messageCount", Math.max(0L, end - beginning));
                            return offsetRow;
                        } catch (Exception e) {
                            Map<String, Object> offsetRow = new LinkedHashMap<>();
                            offsetRow.put("partition", -1);
                            offsetRow.put("error", rootMessage(e));
                            return offsetRow;
                        }
                    })
                    .toList();

            long messageCount = offsetRows.stream()
                    .map(rowMap -> rowMap.get("messageCount"))
                    .filter(Long.class::isInstance)
                    .map(Long.class::cast)
                    .reduce(0L, Long::sum);

            row.put("offsets", offsetRows);
            row.put("messageCount", messageCount);
            row.put("error", null);
            return row;
        } catch (Exception e) {
            return topicErrorDetail(topic, e);
        }
    }

    private Map<String, Object> topicErrorDetail(String topic, Exception e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", topic);
        row.put("partitions", null);
        row.put("offsets", List.of());
        row.put("messageCount", null);
        row.put("error", rootMessage(e));
        return row;
    }

    private static String rootMessage(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private AdminClient adminClient() {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers
        ));
    }

    private KafkaConsumer<String, String> resetConsumer() {
        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put("bootstrap.servers", bootstrapServers);
        consumerConfig.put("group.id", "llm-reset-" + UUID.randomUUID());
        consumerConfig.put("key.deserializer", StringDeserializer.class.getName());
        consumerConfig.put("value.deserializer", StringDeserializer.class.getName());
        consumerConfig.put("auto.offset.reset", "earliest");
        return new KafkaConsumer<>(consumerConfig);
    }

    private List<TopicPartition> topicPartitions(AdminClient adminClient) throws Exception {
        return adminClient.describeTopics(configuredTopicNames()).allTopicNames().get().entrySet().stream()
                .flatMap(entry -> entry.getValue().partitions().stream()
                        .map(partition -> new TopicPartition(entry.getKey(), partition.partition())))
                .toList();
    }
}
