package us.nusun.llmshare.framework.state;

import us.nusun.llmshare.framework.model.ThoughtFrame;

import java.util.ArrayList;
import java.util.Collections;
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

    public List<ThoughtFrame> writable(int depth) {
        if (depth <= 0 || frames.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, frames.size() - depth);
        ArrayList<ThoughtFrame> result = new ArrayList<>(frames.subList(from, frames.size()));
        Collections.reverse(result);
        return List.copyOf(result);
    }

    public boolean contains(String id) {
        return frames.stream().anyMatch(frame -> frame.id().equals(id));
    }

    public void rewrite(String id, String content) {
        for (int i = 0; i < frames.size(); i++) {
            ThoughtFrame frame = frames.get(i);
            if (frame.id().equals(id)) {
                frames.set(i, frame.withContent(clampToApproxTokens(content, frame.maxTokens())));
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
}
