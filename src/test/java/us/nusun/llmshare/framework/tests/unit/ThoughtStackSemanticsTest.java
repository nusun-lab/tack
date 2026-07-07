package us.nusun.llmshare.framework.tests.unit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.state.ThoughtStack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThoughtStackSemanticsTest {
    @Test
    void writableFramesComeFromTheTopOfTheActiveScopeStack() {
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("mission", "Mission context", 100, "broad"));
        stack.push(new ThoughtFrame("routine", "Routine context", 100, "focused"));
        stack.push(new ThoughtFrame("loop", "Loop context", 100, "local"));

        assertEquals(
                java.util.List.of("loop", "routine"),
                stack.writable(2).stream().map(ThoughtFrame::id).toList());
    }

    @Test
    void newThoughtFramesHaveUpdatedAtMetadata() {
        ThoughtFrame frame = new ThoughtFrame("t1", "scope-name", "Purpose", 100, "content");

        assertTrue(frame.updatedAt() != null);
    }

    @Test
    void rewriteUpdatesExistingFrameAndBoundsContentToTokenBudget() {
        ThoughtStack stack = new ThoughtStack();
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        Instant rewritten = Instant.parse("2026-01-01T00:00:10Z");
        stack.push(new ThoughtFrame("t1", "scope-name", "Tiny frame", 2, "old", old));

        stack.rewrite("t1", "abcdefghijklmnopqrstuvwxyz", rewritten);

        assertEquals("scope-name", stack.frames().getFirst().name());
        assertEquals(rewritten, stack.frames().getFirst().updatedAt());
        String content = stack.frames().getFirst().content();
        assertTrue(content.endsWith("[truncated]"));
        assertTrue(content.length() <= 20);
    }

    @Test
    void blankPoppedThoughtDoesNotModifyParent() {
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "parent-scope", "Parent", 100, "parent memory"));
        stack.push(new ThoughtFrame("child", "child-scope", "Child", 100, "   "));

        ThoughtFrame popped = stack.pop();

        assertEquals("child", popped.id());
        assertEquals(1, stack.frames().size());
        assertEquals("parent memory", stack.frames().getFirst().content());
    }

    @Test
    void nonEmptyPoppedThoughtCarriesForwardIntoParentWithScopeName() {
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "parent-scope", "Parent", 100, "parent memory"));
        stack.push(new ThoughtFrame("child", "child-scope", "Child", 100, "project tracker id PT-1"));

        stack.pop();

        String parent = stack.frames().getFirst().content();
        assertTrue(parent.contains("parent memory"));
        assertTrue(parent.contains("From scope child-scope (child):"));
        assertTrue(parent.contains("project tracker id PT-1"));
    }

    @Test
    void carriedForwardThoughtContentIsBoundedByParentBudgetAndPrefersRecentContent() {
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "parent-scope", "Parent", 12, "old ".repeat(50)));
        stack.push(new ThoughtFrame("child", "child-scope", "Child", 100, "important durable anchor ABC-123"));

        stack.pop();

        String parent = stack.frames().getFirst().content();
        assertTrue(parent.length() <= 48);
        assertTrue(parent.contains("ABC-123"));
    }

    @Test
    void poppingNonEmptyThoughtWithNoParentDoesNotFail() {
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("root", "root-scope", "Root", 100, "root content"));

        ThoughtFrame popped = stack.pop();

        assertEquals("root", popped.id());
        assertTrue(stack.frames().isEmpty());
    }

    @Test
    void poppingAnEmptyThoughtScopeFailsClearly() {
        ThoughtStack stack = new ThoughtStack();

        IllegalStateException failure = assertThrows(IllegalStateException.class, stack::pop);
        assertEquals("Cannot pop an empty ThoughtStack", failure.getMessage());
    }
}
