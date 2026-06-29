package us.nusun.llmshare.framework.tests.boundary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.mcp.BoundedMcpToolResultExtractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedMcpToolResultExtractorBoundaryTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SUPPRESSED_MESSAGE =
            "Tool result suppressed by policy because it exceeded the maximum allowed size. "
                    + "Make a more precise tool call with a smaller result.";

    @Test
    void delegatesTextExtractionAndJoinsMultipleFragmentsWhenWithinLimit() {
        BoundedMcpToolResultExtractor extractor = new BoundedMcpToolResultExtractor(20);

        ToolExecutionResult result = extractor.extract(textContent("alpha", "beta"), false);

        assertFalse(result.isError());
        assertEquals("alpha\nbeta", result.resultText());
    }

    @Test
    void preservesDelegateErrorFlagWhenErrorResultIsWithinLimit() {
        BoundedMcpToolResultExtractor extractor = new BoundedMcpToolResultExtractor(20);

        ToolExecutionResult result = extractor.extract(textContent("tool failed"), true);

        assertTrue(result.isError());
        assertEquals("tool failed", result.resultText());
    }

    @Test
    void allowsResultTextAtExactlyConfiguredLimit() {
        BoundedMcpToolResultExtractor extractor = new BoundedMcpToolResultExtractor(10);

        ToolExecutionResult result = extractor.extract(textContent("0123456789"), false);

        assertFalse(result.isError());
        assertEquals("0123456789", result.resultText());
    }

    @Test
    void suppressesResultTextBeyondConfiguredLimitAndMarksItAsError() {
        BoundedMcpToolResultExtractor extractor = new BoundedMcpToolResultExtractor(10);

        ToolExecutionResult result = extractor.extract(textContent("01234567890"), false);

        assertTrue(result.isError());
        assertEquals(SUPPRESSED_MESSAGE, result.resultText());
    }

    @Test
    void appliesConfiguredLimitAfterDelegateJoinsFragmentsWithNewlines() {
        BoundedMcpToolResultExtractor extractor = new BoundedMcpToolResultExtractor(10);

        ToolExecutionResult result = extractor.extract(textContent("12345", "67890"), false);

        assertTrue(result.isError());
        assertEquals(SUPPRESSED_MESSAGE, result.resultText());
    }

    @Test
    void fallsBackToDefaultLimitWhenConfiguredLimitIsNotPositive() {
        BoundedMcpToolResultExtractor extractor = new BoundedMcpToolResultExtractor(0);

        ToolExecutionResult allowed = extractor.extract(textContent("x".repeat(1000)), false);
        ToolExecutionResult suppressed = extractor.extract(textContent("x".repeat(1001)), false);

        assertFalse(allowed.isError());
        assertEquals(1000, allowed.resultText().length());
        assertTrue(suppressed.isError());
        assertEquals(SUPPRESSED_MESSAGE, suppressed.resultText());
    }

    @Test
    void propagatesDelegateFailureForUnsupportedContentTypes() {
        BoundedMcpToolResultExtractor extractor = new BoundedMcpToolResultExtractor(100);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> extractor.extract(content("{\"type\":\"image\"}"), false));

        assertEquals("Unsupported content type: \"image\"", failure.getMessage());
    }

    private static JsonNode textContent(String... fragments) {
        ArrayNode content = OBJECT_MAPPER.createArrayNode();
        for (String fragment : fragments) {
            content.addObject()
                    .put("type", "text")
                    .put("text", fragment);
        }
        return content;
    }

    private static JsonNode content(String itemJson) {
        ArrayNode content = OBJECT_MAPPER.createArrayNode();
        try {
            content.add(OBJECT_MAPPER.readTree(itemJson));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return content;
    }
}
