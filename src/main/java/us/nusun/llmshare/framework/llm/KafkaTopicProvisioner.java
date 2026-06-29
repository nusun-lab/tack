package us.nusun.llmshare.framework.llm;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import us.nusun.llmshare.framework.inventory.ExecutionRuntimeRegistry;
import us.nusun.llmshare.framework.model.InferenceLaneDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@ApplicationScoped
public class KafkaTopicProvisioner {
    private static final Logger LOG = Logger.getLogger(KafkaTopicProvisioner.class);

    @jakarta.inject.Inject ExecutionRuntimeRegistry runtimes;
    @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers;
    @ConfigProperty(name = "llm.topic.dead-letter", defaultValue = "llm.dead-letter") String deadLetterTopic;
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
        for (InferenceLaneDefinition lane : runtimes.allLanes()) {
            topics.add(lane.requestTopic());
            topics.add(lane.responseTopic());
        }
        topics.add(deadLetterTopic);
        return topics.stream().distinct().toList();
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
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException("kafka.bootstrap.servers is required when llm.kafka.provision-topics=true");
        }
        try (Admin adminClient = adminClient()) {
            Set<String> existingTopics = existingTopicNames(adminClient);
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

    protected Set<String> existingTopicNames(Admin adminClient) throws Exception {
        return adminClient.listTopics().names().get();
    }

    protected void createTopics(Admin adminClient, List<NewTopic> topics) throws Exception {
        adminClient.createTopics(topics).all().get();
    }

    protected Admin adminClient() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }
}
