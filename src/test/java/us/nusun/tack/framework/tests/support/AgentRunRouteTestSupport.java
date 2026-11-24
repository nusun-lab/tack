package us.nusun.tack.framework.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import us.nusun.tack.framework.inventory.PersonaCatalog;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.model.AgentRunProjection;
import us.nusun.tack.framework.model.AgentRunScheduled;
import us.nusun.tack.framework.model.RoutineDefinition;
import us.nusun.tack.framework.projection.AgentRunProjectionManager;
import us.nusun.tack.framework.projection.ProjectionStore;
import us.nusun.tack.framework.route.AgentRunRoute;
import us.nusun.tack.framework.route.MissionRoute;
import us.nusun.tack.framework.sandbox.PersonaSandboxManager;
import us.nusun.tack.framework.service.AgentRunAdmissionService;
import us.nusun.tack.framework.state.AgentRunStore;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public final class AgentRunRouteTestSupport {
    public static final String SCHEDULED_TOPIC = "agent-runs.scheduled";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgentRunRouteTestSupport() {
    }

    public static DefaultCamelContext context(
            AgentRunProjectionManager manager,
            RoutineCatalog routines,
            AgentRunStore agentRuns,
            StubKafkaComponent kafka,
            String consumerGroup) throws Exception {
        return context(manager, routines, agentRuns, kafka, consumerGroup, FrameworkFixtures.personaCatalog(), null);
    }

    public static DefaultCamelContext context(
            AgentRunProjectionManager manager,
            RoutineCatalog routines,
            AgentRunStore agentRuns,
            StubKafkaComponent kafka,
            String consumerGroup,
            PersonaCatalog personas,
            PersonaSandboxManager sandboxes) throws Exception {
        DefaultCamelContext context = TestCamelContexts.quiet();
        context.setAutowiredEnabled(false);
        context.getShutdownStrategy().setTimeout(1);
        context.getShutdownStrategy().setSuppressLoggingOnTimeout(true);
        Properties properties = new Properties();
        properties.setProperty("agent-run.scheduled.topic", SCHEDULED_TOPIC);
        properties.setProperty("agent-run.scheduled.consumer.group", consumerGroup);
        context.getPropertiesComponent().setOverrideProperties(properties);
        context.addComponent("kafka", kafka);

        AgentRunAdmissionService admissions = new AgentRunAdmissionService();
        FrameworkFixtures.set(admissions, "personas", personas);
        FrameworkFixtures.set(admissions, "sandboxes", sandboxes);
        FrameworkFixtures.set(admissions, "routines", routines);
        FrameworkFixtures.set(admissions, "projections", manager);
        FrameworkFixtures.set(admissions, "agentRuns", agentRuns);

        AgentRunRoute agentRunRoute = new AgentRunRoute();
        FrameworkFixtures.set(agentRunRoute, "personas", personas);
        FrameworkFixtures.set(agentRunRoute, "routines", routines);
        FrameworkFixtures.set(agentRunRoute, "projections", manager);
        FrameworkFixtures.set(agentRunRoute, "agentRuns", agentRuns);
        FrameworkFixtures.set(agentRunRoute, "admissions", admissions);
        FrameworkFixtures.set(agentRunRoute, "mapper", OBJECT_MAPPER);

        MissionRoute missionRoute = new MissionRoute();
        FrameworkFixtures.set(missionRoute, "admissions", admissions);

        context.addRoutes(missionRoute);
        context.addRoutes(agentRunRoute);
        return context;
    }

    public static RouteBuilder noopRoute(String uri) {
        return new RouteBuilder() {
            @Override
            public void configure() {
                from(uri).setBody(constant("ok"));
            }
        };
    }

    public static RoutineDefinition routine(String id, String routeUri) {
        return RoutineDefinition.builder(id).name(id).description(id).routeUri(routeUri).build();
    }

    public static RoutineDefinition routineWithSubroutine(String id, String routeUri, String subroutineId, String subroutineRouteUri) {
        return RoutineDefinition.builder(id)
                .name(id)
                .description(id)
                .routeUri(routeUri)
                .subroutine(subroutineId, "Run " + subroutineId, subroutineRouteUri)
                .build();
    }

    public static AgentRunScheduled scheduledEvent(StubKafkaComponent kafka, int index) throws Exception {
        Exchange exchange = kafka.sentTo(SCHEDULED_TOPIC).get(index);
        String json = exchange.getIn().getBody(String.class);
        exchange.getIn().setBody(json);
        return OBJECT_MAPPER.readValue(json, AgentRunScheduled.class);
    }

    public static boolean waitForEvent(ProjectionStore store, String runId, String event) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            AgentRunProjection projection = store.get(runId);
            if (projection != null && events(projection).contains(event)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    public static List<String> events(AgentRunProjection projection) {
        return projection.timeline().stream().map(row -> String.valueOf(row.get("type"))).toList();
    }
}
