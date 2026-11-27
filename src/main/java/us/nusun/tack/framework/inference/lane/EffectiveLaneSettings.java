package us.nusun.tack.framework.inference.lane;

import org.eclipse.microprofile.config.Config;

import static us.nusun.tack.framework.FrameworkText.firstNonBlank;

public record EffectiveLaneSettings(
        String baseUrl,
        String apiKey,
        String model,
        double temperature,
        double topP,
        double presencePenalty,
        int topK,
        double minP,
        double repetitionPenalty,
        long timeoutMs,
        boolean thinkingOff,
        String reasoningEffort,
        boolean strictJsonSchema,
        ToolPassSettings toolPass
) {
    public static EffectiveLaneSettings resolve(InferenceLaneDefinition lane, ModelConfiguration defaults,
                                               LaneConfiguration lanes, Config config) {
        var model = defaults.openai();
        var memory = defaults.toolMemory();
        var override = lane.configName() == null ? null : lanes.lanes().get(lane.configName());
        var toolMemory = override == null ? null : override.toolMemory();
        String configuredKey = override == null || override.apiKey().isEmpty()
                ? (blank(lane.apiKeyConfigKey()) ? null : config.getOptionalValue(lane.apiKeyConfigKey(), String.class).orElse(null))
                : override.apiKey().get();
        String environmentKey = blank(lane.apiKeyEnvVariable()) ? null : System.getenv(lane.apiKeyEnvVariable());

        return new EffectiveLaneSettings(
                firstNonBlank(lane.endpoint(), model.baseUrl()),
                firstNonBlank(configuredKey, environmentKey, model.apiKey()),
                firstNonBlank(lane.model(), model.model()),
                override == null ? model.temperature() : override.temperature().orElse(model.temperature()),
                override == null ? model.topP() : override.topP().orElse(model.topP()),
                override == null ? model.presencePenalty() : override.presencePenalty().orElse(model.presencePenalty()),
                override == null ? model.topK() : override.topK().orElse(model.topK()),
                override == null ? model.minP() : override.minP().orElse(model.minP()),
                override == null ? model.repetitionPenalty() : override.repetitionPenalty().orElse(model.repetitionPenalty()),
                model.timeoutMs(),
                model.thinkingOff().enabled(),
                override == null ? model.reasoningEffort().orElse(null)
                        : override.reasoningEffort().filter(value -> !value.isBlank()).or(() -> model.reasoningEffort()).orElse(null),
                model.strictJsonSchema(),
                new ToolPassSettings(
                        toolMemory == null ? memory.maxTokens() : toolMemory.maxTokens().orElse(memory.maxTokens()),
                        override == null ? defaults.maxToolCallingRoundTrips()
                                : override.maxToolCallingRoundTrips().orElse(defaults.maxToolCallingRoundTrips()),
                        override == null ? model.contextWindow() : override.contextWindow().orElse(model.contextWindow()),
                        override == null ? model.maxTokens() : override.maxTokens().orElse(model.maxTokens())));
    }

    private static boolean blank(String key) {
        return key == null || key.isBlank();
    }
}
