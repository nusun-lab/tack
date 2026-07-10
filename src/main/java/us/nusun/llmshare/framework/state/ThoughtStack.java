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
        ThoughtFrame popped = frames.remove(frames.size() - 1);
        carryForwardIntoParent(popped);
        return popped;
    }

    private void carryForwardIntoParent(ThoughtFrame popped) {
        if (frames.isEmpty() || popped.content() == null || popped.content().isBlank()) {
            return;
        }
        int parentIndex = frames.size() - 1;
        ThoughtFrame parent = frames.get(parentIndex);
        String carry = "From scope " + popped.name() + " (" + popped.id() + "):\n" + popped.content().strip();
        frames.set(parentIndex, parent.withContent(mergeCarryForward(parent.content(), carry, parent.maxTokens()), Instant.now()));
    }

    public List<ThoughtFrame> frames() {
        return List.copyOf(frames);
    }

    public boolean contains(String id) {
        return frames.stream().anyMatch(frame -> frame.id().equals(id));
    }

    public void rewrite(String id, String content) {
        rewrite(id, content, Instant.now());
    }

    public void rewrite(String id, String content, Instant updatedAt) {
        for (int i = 0; i < frames.size(); i++) {
            ThoughtFrame frame = frames.get(i);
            if (frame.id().equals(id)) {
                frames.set(i, frame.withContent(clampToApproxTokens(content, frame.maxTokens()), updatedAt));
                return;
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

    private static String mergeCarryForward(String existing, String carry, int maxTokens) {
        String merged = (existing == null || existing.isBlank() ? "" : existing.strip() + "\n") + carry.strip();
        int maxChars = Math.max(1, maxTokens) * 4;
        if (merged.length() <= maxChars) {
            return merged;
        }
        String marker = "[earlier thought content truncated]\n";
        int keep = Math.max(0, maxChars - marker.length());
        return marker + merged.substring(Math.max(0, merged.length() - keep)).strip();
    }
}
