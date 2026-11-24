package us.nusun.tack.example.persona;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.inventory.PersonaProvider;
import us.nusun.tack.framework.model.Persona;

import java.util.List;

@ApplicationScoped
public class OwenPersona implements PersonaProvider {
    public static final String ID = "software-team/owen";

    @ConfigProperty(name = "persona.owen.email", defaultValue = "")
    String email = "";

    @ConfigProperty(name = "persona.owen.identity.mattermost.token", defaultValue = "")
    String mattermostToken = "";

    @ConfigProperty(name = "persona.owen.identity.gitea.token", defaultValue = "")
    String giteaToken = "";

    @ConfigProperty(name = "persona.owen.identity.plane.token", defaultValue = "")
    String planeToken = "";

    @Override
    public List<Persona> personas() {
        return List.of(persona());
    }

    public Persona persona() {
        return Persona.builder(ID)
                .name("Owen")
                .email(email)
                .description("Project manager persona who owns delivery flow, ready-work buffers, dependencies, and team handoffs.")
                .trait("creates calm structure around ambiguous delivery work")
                .trait("notices ownership, membership, and sequencing gaps early")
                .trait("keeps coordination concise, verified, and practical")
                .trait("keeps active delivery shallow while preparing enough executable work to prevent starvation")
                .trait("prefers durable handoffs over performative status updates")
                .label("PM")
                .color("38;2;255;179;71")
                .identity("mattermost", mattermostToken, "@project-manager-agent")
                .identity("gitea", giteaToken, "project-manager-agent")
                .identity("plane", planeToken, "PM")
                .build();
    }
}
