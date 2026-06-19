package us.nusun.llmshare.tests.support;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class SchemaLiveTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "llm.mock.enabled", "false",
                "llm.worker.enabled", "true",
                "llm.kafka.provision-topics", "true",
                "llm.structured-output.provider-schema-enabled", "true"
        );
    }
}
