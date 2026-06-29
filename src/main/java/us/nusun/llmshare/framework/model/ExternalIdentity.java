package us.nusun.llmshare.framework.model;

public record ExternalIdentity(
        String platform,
        String tokenConfigKey,
        String tokenEnvVariable,
        String handle
) {
    public ExternalIdentity {
        if (blank(platform)) {
            throw new IllegalArgumentException("External identity platform is required");
        }
        if (blank(tokenConfigKey)) {
            throw new IllegalArgumentException("External identity token config key is required for " + platform);
        }
        if (blank(tokenEnvVariable)) {
            throw new IllegalArgumentException("External identity token environment variable is required for " + platform);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
