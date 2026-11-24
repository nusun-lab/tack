package us.nusun.tack.framework.config;

public record ToolPassSettings(
        int memoryTokens,
        int maxToolCalls,
        double compactionThreshold,
        int recentToolInteractions,
        int contextWindow,
        int maxOutputTokens
) {}
