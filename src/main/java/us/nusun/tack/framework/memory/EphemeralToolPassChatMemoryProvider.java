package us.nusun.tack.framework.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.function.Consumer;

public final class EphemeralToolPassChatMemoryProvider implements ChatMemoryProvider {
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

}
