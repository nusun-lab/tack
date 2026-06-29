package us.nusun.llmshare.framework.model;

public record ThoughtFrame(String id, String context, int maxTokens, String content) {
    public ThoughtFrame withContent(String content) {
        return new ThoughtFrame(id, context, maxTokens, content);
    }
}
