package us.nusun.llmshare.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "agent")
public interface AgentIdentityConfig {
    Mcp mcp();

    Identity identity();

    interface Mcp {
        Logging logging();

        Service mattermost();

        Service gitea();

        PlaneService plane();

        interface Logging {
            @WithDefault("true")
            boolean enabled();

            @WithName("transport-requests")
            @WithDefault("false")
            boolean transportRequests();

            @WithName("transport-responses")
            @WithDefault("false")
            boolean transportResponses();
        }

        interface Service {
            Optional<String> url();

            @WithDefault("streamableHttp")
            String transportType();

            Optional<String> authHeaderName();

            Optional<String> authHeaderValueFormat();

            @WithName("disabled-tools")
            Optional<List<String>> disabledTools();
        }

        interface PlaneService extends Service {
            @WithName("workspace-slug")
            Optional<String> workspaceSlug();
        }
    }

    interface Identity {
        Role director();

        @WithName("project-manager")
        Role projectManager();

        @WithName("product-owner")
        Role productOwner();

        @WithName("application-developer")
        Role applicationDeveloper();

        interface Role {
            TokenService mattermost();

            TokenService gitea();

            TokenService plane();
        }

        interface TokenService {
            Optional<String> token();
        }
    }
}
