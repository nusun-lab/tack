package us.nusun.tack.framework.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "llm.model-default")
public interface ModelConfiguration {
    OpenAi openai();
    ToolMemory toolMemory();
    StructuredOutput structuredOutput();

    @WithDefault("4")
    int maxToolCallingRoundTrips();

    interface OpenAi {
        String baseUrl();
        String apiKey();
        String model();
        double temperature();
        double topP();
        double presencePenalty();
        int topK();
        double minP();
        double repetitionPenalty();
        int maxTokens();
        @WithDefault("69632")
        int contextWindow();
        long timeoutMs();
        Optional<String> reasoningEffort();
        ThinkingOff thinkingOff();
        @WithDefault("true")
        boolean strictJsonSchema();
    }

    interface ThinkingOff {
        boolean enabled();
    }

    interface ToolMemory {
        @WithDefault("12000")
        int maxTokens();
        @WithDefault("0.50")
        double compactionThreshold();
        @WithDefault("3")
        int recentToolInteractions();
    }

    interface StructuredOutput {
        @WithDefault("false")
        boolean providerSchemaEnabled();
    }
}
