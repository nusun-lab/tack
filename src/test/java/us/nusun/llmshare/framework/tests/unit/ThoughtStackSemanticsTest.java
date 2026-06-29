package us.nusun.llmshare.framework.tests.unit;

import org.junit.jupiter.api.Test;
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
    void rewriteUpdatesExistingFrameAndBoundsContentToTokenBudget() {
        ThoughtStack stack = new ThoughtStack();
        stack.push(new ThoughtFrame("t1", "Tiny frame", 2, "old"));

        stack.rewrite("t1", "abcdefghijklmnopqrstuvwxyz");

        String content = stack.frames().getFirst().content();
        assertTrue(content.endsWith("[truncated]"));
        assertTrue(content.length() <= 20);
    }

    @Test
    void poppingAnEmptyThoughtScopeFailsClearly() {
        ThoughtStack stack = new ThoughtStack();

        IllegalStateException failure = assertThrows(IllegalStateException.class, stack::pop);
        assertEquals("Cannot pop an empty ThoughtStack", failure.getMessage());
    }
}
