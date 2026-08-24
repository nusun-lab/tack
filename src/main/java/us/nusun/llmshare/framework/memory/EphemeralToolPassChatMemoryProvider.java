package us.nusun.llmshare.framework.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.TokenCountEstimator;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.function.Consumer;

public final class EphemeralToolPassChatMemoryProvider implements ChatMemoryProvider {
    private static final String MAX_TOOL_ROUND_TRIPS_PROPERTY = "llm.model-default.max-tool-calling-round-trips";
    private static final String COMPACTION_THRESHOLD_PROPERTY = "llm.model-default.tool-memory.compaction-threshold";
    private static final String RECENT_TOOL_INTERACTIONS_PROPERTY = "llm.model-default.tool-memory.recent-tool-interactions";

    private final int maxTokens;
    private final TokenCountEstimator tokenCountEstimator;
    private final boolean alwaysKeepSystemMessageFirst;
    private final int maxToolCalls;
    private final double compactionThreshold;
    private final int recentToolInteractions;
    private final Consumer<String> runtimeWarningSink;

    public EphemeralToolPassChatMemoryProvider(
            int maxTokens,
            TokenCountEstimator tokenCountEstimator,
            boolean alwaysKeepSystemMessageFirst) {
        this(
                maxTokens,
                tokenCountEstimator,
                alwaysKeepSystemMessageFirst,
                configuredInt(MAX_TOOL_ROUND_TRIPS_PROPERTY, 50),
                configuredDouble(COMPACTION_THRESHOLD_PROPERTY, 0.50),
                configuredInt(RECENT_TOOL_INTERACTIONS_PROPERTY, 3),
                null);
    }

    public EphemeralToolPassChatMemoryProvider(
            int maxTokens,
            TokenCountEstimator tokenCountEstimator,
            boolean alwaysKeepSystemMessageFirst,
            Consumer<String> runtimeWarningSink) {
        this(
                maxTokens,
                tokenCountEstimator,
                alwaysKeepSystemMessageFirst,
                configuredInt(MAX_TOOL_ROUND_TRIPS_PROPERTY, 50),
                configuredDouble(COMPACTION_THRESHOLD_PROPERTY, 0.50),
                configuredInt(RECENT_TOOL_INTERACTIONS_PROPERTY, 3),
                runtimeWarningSink);
    }

    public EphemeralToolPassChatMemoryProvider(
            int maxTokens,
            TokenCountEstimator tokenCountEstimator,
            boolean alwaysKeepSystemMessageFirst,
            int maxToolCalls,
            double compactionThreshold,
            int recentToolInteractions) {
        this(maxTokens, tokenCountEstimator, alwaysKeepSystemMessageFirst, maxToolCalls, compactionThreshold,
                recentToolInteractions, null);
    }

    public EphemeralToolPassChatMemoryProvider(
            int maxTokens,
            TokenCountEstimator tokenCountEstimator,
            boolean alwaysKeepSystemMessageFirst,
            int maxToolCalls,
            double compactionThreshold,
            int recentToolInteractions,
            Consumer<String> runtimeWarningSink) {
        this.maxTokens = maxTokens;
        this.tokenCountEstimator = tokenCountEstimator;
        this.alwaysKeepSystemMessageFirst = alwaysKeepSystemMessageFirst;
        this.maxToolCalls = maxToolCalls;
        this.compactionThreshold = compactionThreshold;
        this.recentToolInteractions = recentToolInteractions;
        this.runtimeWarningSink = runtimeWarningSink;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        ChatMemory memory = ToolAwareTokenWindowChatMemory.builder()
                .id(memoryId)
                .maxTokens(maxTokens, tokenCountEstimator)
                .alwaysKeepSystemMessageFirst(alwaysKeepSystemMessageFirst)
                .maxToolCalls(maxToolCalls)
                .compactionThreshold(compactionThreshold)
                .recentToolInteractions(recentToolInteractions)
                .runtimeWarningSink(runtimeWarningSink)
                .build();
        return new ToolLoopGuardChatMemory(memory, maxToolCalls);
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
