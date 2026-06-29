package us.nusun.llmshare.framework.mcp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

@ConfigMapping(prefix = "agent")
public interface AgentIdentityConfig {
    Mcp mcp();

    interface Mcp {
        Logging logging();

        @WithName("transport-timeout-ms")
        @WithDefault("60000")
        long transportTimeoutMs();

        @WithName("initialization-timeout-ms")
        @WithDefault("30000")
        long initializationTimeoutMs();

        @WithName("max-tool-result-characters")
        @WithDefault("1000")
        int maxToolResultCharacters();

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
        }

        interface PlaneService extends Service {
            @WithName("workspace-slug")
            Optional<String> workspaceSlug();
        }
    }

}
