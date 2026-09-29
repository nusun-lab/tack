package us.nusun.llmshare.framework.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QwenTokenCountEstimatorTest {
    private final QwenTokenCountEstimator estimator = new QwenTokenCountEstimator();

    @Test
    void estimatesFromUtf8BytesWithoutExternalTokenizerState() {
        assertEquals(0, estimator.estimateTokenCountInText(null));
        assertEquals(0, estimator.estimateTokenCountInText(""));
        assertEquals(1, estimator.estimateTokenCountInText("abc"));
        assertEquals(2, estimator.estimateTokenCountInText("abcd"));
        assertEquals(1, estimator.estimateTokenCountInText("猫"));
    }

    @Test
    void scalesWithTypicalStructuredContent() {
        String json = """
                {"tool":"plane","arguments":{"query":"find active project work"}}
                """;
        assertTrue(estimator.estimateTokenCountInText(json) > 10);
    }
}
