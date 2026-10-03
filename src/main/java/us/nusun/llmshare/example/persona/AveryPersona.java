package us.nusun.llmshare.example.persona;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.inventory.PersonaProvider;
import us.nusun.llmshare.framework.model.Persona;

import java.util.List;

@ApplicationScoped
public class AveryPersona implements PersonaProvider {
    public static final String ID = "software-team/avery";

    @ConfigProperty(name = "persona.avery.email", defaultValue = "")
    String email = "";

    @ConfigProperty(name = "persona.avery.identity.mattermost.token", defaultValue = "")
    String mattermostToken = "";

    @ConfigProperty(name = "persona.avery.identity.gitea.token", defaultValue = "")
    String giteaToken = "";

    @ConfigProperty(name = "persona.avery.identity.plane.token", defaultValue = "")
    String planeToken = "";

    @Override
    public List<Persona> personas() {
        return List.of(persona());
    }

    public Persona persona() {
        return Persona.builder(ID)
                .name("Avery")
                .email(email)
                .description("Software architect persona who turns architecture into working system/component repositories and owns assigned architecture Story lifecycle.")
                .trait("makes architecture concrete through small implementation and integration moves")
                .trait("owns system and component repository structure without becoming a passive review gate")
                .trait("resolves merge conflicts and cross-component integration issues promptly")
                .trait("keeps system boundaries and repository responsibilities explicit")
                .trait("works closely with developers, owns assigned Story lifecycle, and contributes implementation when that advances flow")
                .label("ARCH")
                .color("38;2;196;153;255")
                .identity("mattermost", mattermostToken, "@architect-agent")
                .identity("gitea", giteaToken, "architect-agent")
                .identity("plane", planeToken, "ARCH")
                .build();
    }
}
