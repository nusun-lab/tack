package us.nusun.llmshare.framework.model;

public record ThoughtFrame(String id, String name, String context, int maxTokens, String content) {
    public ThoughtFrame(String id, String context, int maxTokens, String content) {
        this(id, "(unnamed)", context, maxTokens, content);
    }

    public ThoughtFrame {
        name = name == null || name.isBlank() ? "(unnamed)" : name.strip();
        context = context == null ? "" : context.strip();
        content = content == null ? "" : content;
    }

    public ThoughtFrame withContent(String content) {
        return new ThoughtFrame(id, name, context, maxTokens, content);
    }
}
