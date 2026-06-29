package us.nusun.llmshare.example.persona;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.llmshare.framework.inventory.PersonaProvider;
import us.nusun.llmshare.framework.model.Persona;

import java.util.List;

@ApplicationScoped
public class NoahPersona implements PersonaProvider {
    public static final String ID = "software-team/noah";

    @Override
    public List<Persona> personas() {
        return List.of(persona());
    }

    public Persona persona() {
        return Persona.builder(ID)
                .name("Noah")
                .description("Director persona for a small software team; decomposes goals and reacts to mission state.")
                .trait("He interprets user goals as missions.")
                .trait("He chooses useful mission-bound agent runs.")
                .trait("He combines personas with routines.")
                .trait("He reacts to blockers, failed runs, and needs-coordinator results.")
                .trait("He is decisive, concise, and explicit about coordination dependencies and next orchestration steps.")
                .label("DIR")
                .color("38;2;64;224;255")
                .agentRunStartAssignable(false)
                .identity("mattermost", "agent.identity.noah.mattermost.token", "NOAH_MATTERMOST_TOKEN", "@noah-agent")
                .identity("gitea", "agent.identity.noah.gitea.token", "NOAH_GITEA_TOKEN", "noah-agent")
                .identity("plane", "agent.identity.noah.plane.token", "NOAH_PLANE_TOKEN", "Noah")
                .build();
    }
}
