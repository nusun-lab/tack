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
