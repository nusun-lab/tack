package us.nusun.llmshare.framework.model;

import java.time.Instant;

public record ThoughtFrame(String id, String name, String context, int maxTokens, String content, Instant updatedAt) {
    public ThoughtFrame(String id, String context, int maxTokens, String content) {
        this(id, "(unnamed)", context, maxTokens, content, Instant.now());
    }

    public ThoughtFrame(String id, String name, String context, int maxTokens, String content) {
        this(id, name, context, maxTokens, content, Instant.now());
    }

    public ThoughtFrame {
        name = name == null || name.isBlank() ? "(unnamed)" : name.strip();
        context = context == null ? "" : context.strip();
        content = content == null ? "" : content;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public ThoughtFrame withContent(String content) {
        return withContent(content, Instant.now());
    }

    public ThoughtFrame withContent(String content, Instant updatedAt) {
        return new ThoughtFrame(id, name, context, maxTokens, content, updatedAt);
    }
}
