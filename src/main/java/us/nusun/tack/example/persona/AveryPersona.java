package us.nusun.tack.example.persona;

import jakarta.enterprise.context.ApplicationScoped;
import us.nusun.tack.framework.config.PersonaAccounts;
import us.nusun.tack.framework.inventory.PersonaProvider;
import us.nusun.tack.framework.model.Persona;

import java.util.List;

@ApplicationScoped
public class AveryPersona implements PersonaProvider {
    public static final String ID = "software-team/avery";

    private final PersonaAccounts.Account account;

    public AveryPersona(PersonaAccounts accounts) {
        this.account = accounts.accounts().get("avery");
    }

    @Override
    public List<Persona> personas() {
        return List.of(persona());
    }

    public Persona persona() {
        return Persona.builder(ID)
                .name("Avery")
                .email(account.email().orElse(""))
                .description("Software architect persona who turns architecture into working system/component repositories and owns assigned architecture Story lifecycle.")
                .trait("makes architecture concrete through small implementation and integration moves")
                .trait("owns system and component repository structure without becoming a passive review gate")
                .trait("resolves merge conflicts and cross-component integration issues promptly")
                .trait("keeps system boundaries and repository responsibilities explicit")
                .trait("works closely with developers, owns assigned Story lifecycle, and contributes implementation when that advances flow")
                .label("ARCH")
                .color("38;2;255;123;172")
                .identity("mattermost", account.token("mattermost"), "@architect-agent")
                .identity("gitea", account.token("gitea"), "architect-agent")
                .identity("plane", account.token("plane"), "ARCH")
                .build();
    }
}
