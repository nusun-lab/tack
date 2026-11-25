package us.nusun.tack.framework.routine;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThoughtStackSemanticsTest {
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
    void poppingAThoughtDoesNotMechanicallyModifyParent() {
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "parent-scope", "Parent", 100, "parent memory"));
        stack.push(new ThoughtFrame("child", "child-scope", "Child", 100, "project tracker id PT-1"));

        ThoughtFrame popped = stack.pop();

        assertEquals("child", popped.id());
        assertEquals(1, stack.frames().size());
        assertEquals("parent memory", stack.frames().getFirst().content());
    }

    @Test
    void leafAndParentExposeCurrentStackTargets() {
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("parent", "parent-scope", "Parent", 100, "parent memory"));
        stack.push(new ThoughtFrame("child", "child-scope", "Child", 100, "child memory"));

        assertEquals("child", stack.leaf().id());
        assertEquals("parent", stack.parentOfLeaf().id());
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
