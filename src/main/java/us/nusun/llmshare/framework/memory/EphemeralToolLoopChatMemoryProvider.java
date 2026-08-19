package us.nusun.llmshare.framework.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.TokenCountEstimator;
import org.eclipse.microprofile.config.ConfigProvider;

public final class EphemeralToolLoopChatMemoryProvider implements ChatMemoryProvider {
    private static final String MAX_TOOL_CALLS_PROPERTY = "llm.model-default.max-tool-calling-round-trips";
    private static final String COMPACTION_THRESHOLD_PROPERTY = "llm.model-default.tool-memory.compaction-threshold";
    private static final String RECENT_TOOL_INTERACTIONS_PROPERTY = "llm.model-default.tool-memory.recent-tool-interactions";

    private final int maxTokens;
    private final TokenCountEstimator tokenCountEstimator;
    private final boolean alwaysKeepSystemMessageFirst;
    private final int maxToolCalls;
    private final double compactionThreshold;
    private final int recentToolInteractions;

    public EphemeralToolLoopChatMemoryProvider(
            int maxTokens,
            TokenCountEstimator tokenCountEstimator,
            boolean alwaysKeepSystemMessageFirst) {
        this(
                maxTokens,
                tokenCountEstimator,
                alwaysKeepSystemMessageFirst,
                configuredInt(MAX_TOOL_CALLS_PROPERTY, 50),
                configuredDouble(COMPACTION_THRESHOLD_PROPERTY, 0.50),
                configuredInt(RECENT_TOOL_INTERACTIONS_PROPERTY, 3));
    }

    public EphemeralToolLoopChatMemoryProvider(
            int maxTokens,
            TokenCountEstimator tokenCountEstimator,
            boolean alwaysKeepSystemMessageFirst,
            int maxToolCalls,
            double compactionThreshold,
            int recentToolInteractions) {
        this.maxTokens = maxTokens;
        this.tokenCountEstimator = tokenCountEstimator;
        this.alwaysKeepSystemMessageFirst = alwaysKeepSystemMessageFirst;
        this.maxToolCalls = maxToolCalls;
        this.compactionThreshold = compactionThreshold;
        this.recentToolInteractions = recentToolInteractions;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        return ToolAwareTokenWindowChatMemory.builder()
                .id(memoryId)
                .maxTokens(maxTokens, tokenCountEstimator)
                .alwaysKeepSystemMessageFirst(alwaysKeepSystemMessageFirst)
                .maxToolCalls(maxToolCalls)
                .compactionThreshold(compactionThreshold)
                .recentToolInteractions(recentToolInteractions)
                .build();
    }

    private static int configuredInt(String name, int fallback) {
        try {
            return ConfigProvider.getConfig().getOptionalValue(name, Integer.class).orElse(fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double configuredDouble(String name, double fallback) {
        try {
            return ConfigProvider.getConfig().getOptionalValue(name, Double.class).orElse(fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
