package us.nusun.tack.framework.route;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.framework.model.MissionRequest;

@ApplicationScoped
public class HttpApiRoute extends RouteBuilder {
    @Override
    public void configure() {
        restConfiguration()
                .component("platform-http")
                .dataFormatProperty("prettyPrint", "true");

        rest("/missions")
                .produces("application/json")
                .consumes("application/json")
                .post()
                    .type(MissionRequest.class)
                    .to("direct:submit-mission");

        rest("/workspace/reset")
                .produces("application/json")
                .post()
                    .to("direct:perform-llm-reset");

        rest("/workspace/view")
                .produces("application/json")
                .get()
                    .to("direct:workspace-view");
    }
}
