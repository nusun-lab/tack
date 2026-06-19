package us.nusun.llmshare.tests.runtime;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.bean.KafkaTopicProvisioner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class KafkaTopicProvisionerTest {
    @Inject
    KafkaTopicProvisioner topicProvisioner;

    @Test
    void exposesConfiguredTopicNamesIncludingDeadLetter() {
        assertEquals(6, topicProvisioner.configuredTopicNames().size());
        assertTrue(topicProvisioner.configuredTopicNames().contains("llm.requests"));
        assertTrue(topicProvisioner.configuredTopicNames().contains("llm.responses"));
        assertTrue(topicProvisioner.configuredTopicNames().contains("llm.dead-letter"));
        assertTrue(topicProvisioner.configuredTopicNames().contains("workspace.project-basics"));
        assertTrue(topicProvisioner.configuredTopicNames().contains("workspace.scratchpads"));
        assertTrue(topicProvisioner.configuredTopicNames().contains("workspace.director-state"));
    }
}
