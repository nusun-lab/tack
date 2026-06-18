package us.nusun.llmshare.model;

public record RoutineToolTraceEntry(
        String service,
        String toolName,
        String arguments,
        boolean error,
        String result
) {
}
