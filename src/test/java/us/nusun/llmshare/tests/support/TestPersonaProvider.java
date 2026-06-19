package us.nusun.llmshare.tests.support;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.team.AgentPersonaDefinition;
import us.nusun.llmshare.team.AgentPersonaProvider;
import java.util.Map;

@ApplicationScoped
public class TestPersonaProvider implements AgentPersonaProvider {
    private static final AgentPersonaDefinition PERSONA = new AgentPersonaDefinition(
            "software-team/test-observer",
            "test-observer",
            "Test Observer",
            "direct:software-team-test-observer",
            "software-team-test-observer",
            "TST",
            "38;2;220;220;120",
            "Test Observer online.",
            "Test Observer offline.",
            "Observe registry discovery and dashboard projection behavior during tests.",
            "You are a test persona.",
            Map.of(
                    "mattermost", "@test-observer",
                    "gitea", "test-observer-bot"
            ),
            true,
            false
    );

    @Override
    public AgentPersonaDefinition persona() {
        return PERSONA;
    }
}
