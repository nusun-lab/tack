package us.nusun.tack.framework.routine;

record ActSynthesisResult(
        String content,
        String summary,
        String outcome,
        boolean durableProgress,
        double confidence
) {
}
