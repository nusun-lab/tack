package us.nusun.tack.framework.inference.lane;

public record ToolPassSettings(
        int memoryTokens,
        int maxToolCalls,
        int contextWindow,
        int maxOutputTokens
) {}
