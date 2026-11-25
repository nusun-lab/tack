package us.nusun.tack.framework.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.framework.model.MissionRequest;
import us.nusun.tack.framework.service.AgentRunAdmissionService;

@ApplicationScoped
public class MissionRoute extends RouteBuilder {
    @Inject AgentRunAdmissionService admissions;

    @Override
    public void configure() {
        errorHandler(noErrorHandler());

        from("direct:start-mission")
                .routeId("start-mission")
                .validate(body().isInstanceOf(MissionRequest.class))
                .bean(admissions, "admitMission")
                .to("direct:publish-accepted-agent-runs")
                .bean(admissions, "acceptedMissionResponse");
    }
}
