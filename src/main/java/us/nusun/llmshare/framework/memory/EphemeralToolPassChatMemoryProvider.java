package us.nusun.llmshare.framework.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.TokenCountEstimator;

public final class EphemeralToolPassChatMemoryProvider implements ChatMemoryProvider {
    private final int maxTokens;
    private final TokenCountEstimator tokenCountEstimator;
    private final boolean alwaysKeepSystemMessageFirst;

    public EphemeralToolPassChatMemoryProvider(
            int maxTokens,
            TokenCountEstimator tokenCountEstimator,
            boolean alwaysKeepSystemMessageFirst) {
        this.maxTokens = maxTokens;
        this.tokenCountEstimator = tokenCountEstimator;
        this.alwaysKeepSystemMessageFirst = alwaysKeepSystemMessageFirst;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        return ToolAwareTokenWindowChatMemory.builder()
                .id(memoryId)
                .maxTokens(maxTokens, tokenCountEstimator)
                .alwaysKeepSystemMessageFirst(alwaysKeepSystemMessageFirst)
                .build();
    }
}
