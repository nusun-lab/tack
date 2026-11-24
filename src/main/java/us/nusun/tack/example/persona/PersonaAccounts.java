package us.nusun.tack.example.persona;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;

import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "persona")
public interface PersonaAccounts {
    @WithParentName
    Map<String, Account> accounts();

    interface Account {
        Optional<String> email();
        Map<String, Identity> identity();

        default String token(String platform) {
            Identity credentials = identity().get(platform);
            return credentials == null ? "" : credentials.token().orElse("");
        }
    }

    interface Identity {
        Optional<String> token();
    }
}
