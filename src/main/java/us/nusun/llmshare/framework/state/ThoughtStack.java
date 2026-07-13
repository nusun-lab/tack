package us.nusun.llmshare.framework.state;

import us.nusun.llmshare.framework.model.ThoughtFrame;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ThoughtStack {
    private final List<ThoughtFrame> frames = new ArrayList<>();

    public void push(ThoughtFrame frame) {
        frames.add(frame);
    }

    public ThoughtFrame pop() {
        if (frames.isEmpty()) {
            throw new IllegalStateException("Cannot pop an empty ThoughtStack");
        }
        return frames.remove(frames.size() - 1);
    }

    public List<ThoughtFrame> frames() {
        return List.copyOf(frames);
    }

    public ThoughtFrame leaf() {
        if (frames.isEmpty()) {
            return null;
        }
        return frames.getLast();
    }

    public ThoughtFrame parentOfLeaf() {
        if (frames.size() < 2) {
            return null;
        }
        return frames.get(frames.size() - 2);
    }

    public boolean contains(String id) {
        return frames.stream().anyMatch(frame -> frame.id().equals(id));
    }

    public ThoughtFrame rewrite(String id, String content) {
        return rewrite(id, content, Instant.now());
    }

    public ThoughtFrame rewrite(String id, String content, Instant updatedAt) {
        for (int i = 0; i < frames.size(); i++) {
            ThoughtFrame frame = frames.get(i);
            if (frame.id().equals(id)) {
                ThoughtFrame rewritten = frame.withContent(clampToApproxTokens(content, frame.maxTokens()), updatedAt);
                frames.set(i, rewritten);
                return rewritten;
            }
        }
        throw new IllegalArgumentException("Unknown ThoughtFrame id: " + id);
    }

    private static String clampToApproxTokens(String value, int maxTokens) {
        if (value == null) {
            return "";
        }
        int maxChars = Math.max(1, maxTokens) * 4;
        if (value.length() <= maxChars) {
            return value.strip();
        }
        return value.substring(0, Math.max(0, maxChars - 12)).strip() + " [truncated]";
    }
}
