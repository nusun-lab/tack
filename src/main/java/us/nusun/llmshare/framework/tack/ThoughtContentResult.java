package us.nusun.llmshare.framework.tack;

record ThoughtContentResult(
        String content,
        String summary,
        String outcome,
        Boolean durableProgress,
        Double confidence
) {
}
