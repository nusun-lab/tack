package us.nusun.llmshare.team;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class SoftwareTeamContext {
    private static final TeamOperatingContext CONTEXT = new TeamOperatingContext(
            "software-team",
            "Software Team",
            "David Gordon",
            "@aphelio",
            "#forum-agents",
            List.of("Mattermost", "Gitea", "Plane"),
            List.of("Backstage"),
            List.of(
                    "Use private team-only collaboration spaces, never public ones.",
                    "Use shared channels only, never direct messages.",
                    "If the correct collaboration location is unknown, fall back to Mattermost channel #forum-agents.",
                    "David Gordon (@aphelio) must always be included in project collaboration spaces."
            ),
            List.of(
                    "Each step must do exactly one small discrete task.",
                    "Do not combine multiple actions in one step.",
                    "Any tool-written text should stay focused and granular, but project basics and scratchpad updates may use roughly 500 to 1000 characters when needed.",
                    "Wiki pages should prefer concise focused updates, but they may exceed 100 characters when needed to preserve useful project context."
            )
    );

    public TeamOperatingContext context() {
        return CONTEXT;
    }
}
