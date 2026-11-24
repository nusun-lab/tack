package us.nusun.tack.example.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.ToolProfileProvider;
import us.nusun.tack.framework.model.ToolProfile;

import java.util.List;

@ApplicationScoped
public class QualitySourceControlWriteToolProfile implements ToolProfileProvider {
    public static final String ID = "quality-source-control-write";

    private final GiteaMcpServer gitea;
    private final int maxResultTokens;

    @Inject
    public QualitySourceControlWriteToolProfile(
            GiteaMcpServer gitea,
            @ConfigProperty(name = "agent.tool.profile.source-control.max-result-tokens", defaultValue = "6000")
            int maxResultTokens) {
        this.gitea = gitea;
        this.maxResultTokens = maxResultTokens;
    }

    @Override
    public List<ToolProfile> toolProfiles() {
        return List.of(RepositoryWriting.profile(ID, gitea)
                .promptGuidance("""
                        - this profile changes QA-owned test/evidence source in the existing system repository selected by
                          the calling routine; it cannot provision repositories
                        - do not use this profile to modify component implementation
                        """ + RepositoryWriting.REPOSITORY_GUIDANCE + """
                        - use ordinary git/filesystem/build/test commands for edits, validation, commits, and pushes
                        - only an actual auth/write failure establishes a write-permission blocker
                        - validate directly in the sandbox; Gitea Actions/CI are not enabled
                        - never claim source changes, validation, commits, or pushes without direct evidence
                        """)
                .maxResultTokens(maxResultTokens)
                .build());
    }
}
