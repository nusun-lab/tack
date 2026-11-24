package us.nusun.tack.framework.tack;

record ActSynthesisResult(
        String content,
        String summary,
        String outcome,
        boolean durableProgress,
        double confidence
) {
}
