package us.nusun.llmshare.framework.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.TokenCountEstimator;

public final class EphemeralToolLoopChatMemoryProvider implements ChatMemoryProvider {
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
        this(maxTokens, tokenCountEstimator, alwaysKeepSystemMessageFirst, 0, 0.50, 3);
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
}
