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
                .bean(admissions, "prepareMission")
                .setProperty("missionAdmissionPlan", body())
                .setProperty("mission", simple("${body.mission}"))
                .bean(admissions, "commitMission")
                .setProperty("agentRunHandles", method(admissions, "handles"))
                .split(body())
                    .setBody(simple("${body.event}"))
                    .to("direct:publish-agent-run-scheduled")
                .end()
                .bean(admissions, "acceptedMissionResponse")
                .removeProperty("missionAdmissionPlan")
                .removeProperty("agentRunHandles");
    }

}
