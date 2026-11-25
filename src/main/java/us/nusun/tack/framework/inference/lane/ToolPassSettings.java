package us.nusun.tack.framework.inference.lane;

public record ToolPassSettings(
        int memoryTokens,
        int maxToolCalls,
        double compactionThreshold,
        int recentToolInteractions,
        int contextWindow,
        int maxOutputTokens
) {}
