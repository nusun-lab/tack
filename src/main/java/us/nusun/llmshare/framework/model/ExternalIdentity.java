package us.nusun.llmshare.framework.model;

public record ExternalIdentity(
        String platform,
        String token,
        String handle
) {
    public ExternalIdentity {
        if (blank(platform)) {
            throw new IllegalArgumentException("External identity platform is required");
        }
        token = token == null ? "" : token;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
