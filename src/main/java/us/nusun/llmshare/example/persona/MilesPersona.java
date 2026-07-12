package us.nusun.llmshare.example.persona;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.PersonaProvider;
import us.nusun.llmshare.framework.model.Persona;

import java.util.List;

@ApplicationScoped
public class MilesPersona implements PersonaProvider {
    public static final String ID = "software-team/miles";

    @ConfigProperty(name = "persona.miles.identity.mattermost.token", defaultValue = "")
    String mattermostToken = "";

    @ConfigProperty(name = "persona.miles.identity.gitea.token", defaultValue = "")
    String giteaToken = "";

    @ConfigProperty(name = "persona.miles.identity.plane.token", defaultValue = "")
    String planeToken = "";

    @Override
    public List<Persona> personas() {
        return List.of(persona());
    }

    public Persona persona() {
        return Persona.builder(ID)
                .name("Miles")
                .description("Application developer persona who drives implementation and PR-based delivery.")
                .trait("He inspects assigned work and source repository readiness.")
                .trait("He creates or updates failing tests before implementation when appropriate.")
                .trait("He performs small safe delivery steps.")
                .trait("He uses the source repository named in project context rather than inventing one.")
                .trait("He is technical, test-oriented, direct, and prefers verified repository/tool state over assumptions.")
                .label("DEV")
                .color("38;2;106;169;255")
                .identity("mattermost", mattermostToken, "@app-developer-agent")
                .identity("gitea", giteaToken, "application-developer-agent")
                .identity("plane", planeToken, "Dev")
                .build();
    }
}
