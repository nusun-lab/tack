package us.nusun.tack.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.junit.jupiter.api.Test;
import us.nusun.tack.example.routine.ApplicationDeveloperRoutine;
import us.nusun.tack.example.routine.ProjectManagerRoutine;
import us.nusun.tack.example.routine.QualityEngineerRoutine;
import us.nusun.tack.example.tool.DeliveryManagementToolProfile;
import us.nusun.tack.example.tool.EngineeringWorkToolProfile;
import us.nusun.tack.example.tool.QualityManagementToolProfile;
import us.nusun.tack.example.tool.QualitySourceControlWriteToolProfile;
import us.nusun.tack.example.tool.SourceControlToolProfile;
import us.nusun.tack.example.tool.SourceControlWriteToolProfile;
import us.nusun.tack.example.tool.TeamChatToolProfile;
import us.nusun.tack.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.tack.framework.inference.LlmJobEnvelope;
import us.nusun.tack.framework.inference.LlmJobResult;
import us.nusun.tack.framework.inference.LlmPassType;
import us.nusun.tack.framework.persona.PersonaAccounts;
import us.nusun.tack.framework.routine.PrimitiveExecutor;
import us.nusun.tack.framework.routine.TackComponent;
import us.nusun.tack.framework.routine.ThoughtStack;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestCamelContexts;
import us.nusun.tack.framework.tool.ToolProfile;
import us.nusun.tack.framework.tool.ToolProfileRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareTeamWorkflowRouteTest {
    @Test
    void pmPlanningAndActiveFlowRunAsIndependentFocusedEntries() throws Exception {
        var config = new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(Map.of(
                "persona.miles.email", "miles@example.test", "persona.avery.email", "avery@example.test"),
                "engineering accounts", 100)).withMapping(PersonaAccounts.class).build();
        var accounts = config.getConfigMapping(PersonaAccounts.class);
        var planner = new ProjectManagerRoutine(accounts);
        Function<String, String> noDecision = id -> { throw new AssertionError("Unexpected decision " + id); };

        var planning = run(planner, planner.routine().routeUri("planning"), noDecision);
        assertEquals(List.of("pm-planning", "pm-awareness"), actionIds(planning));

        var manager = new ProjectManagerRoutine(accounts);
        var flow = run(manager, manager.routine().routeUri("flow"), noDecision);
        assertEquals(List.of("pm-flow", "pm-awareness"), actionIds(flow));
    }

    @Test
    void developerKeepsPlanningDeliveryValidationAndKaneoHandoffSeparate() throws Exception {
        var requests = run(new ApplicationDeveloperRoutine(), "direct:dev-focus-implementation", decision -> switch (decision) {
            case "developer-has-work", "developer-has-valid-task-context", "developer-implementation-complete" -> "yes";
            case "developer-is-topology-investigation" -> "no";
            default -> throw new AssertionError("Unexpected decision " + decision);
        });

        assertEquals(List.of("dev-work-selection", "dev-task-context-read", "dev-source-context-plan",
                "dev-delivery-step", "dev-validation-step", "dev-task-evidence-update", "dev-documentation-closeout",
                "dev-team-chat-closeout"), actionIds(requests));
        assertTrue(requests.stream().anyMatch(job -> job.passType() == LlmPassType.CONSOLIDATION));
    }

    @Test
    void developerTopologyInvestigationStaysOnItsOwnEvidencePath() throws Exception {
        var requests = run(new ApplicationDeveloperRoutine(), "direct:dev-focus-implementation", decision -> switch (decision) {
            case "developer-has-work", "developer-has-valid-task-context", "developer-implementation-complete",
                 "developer-is-topology-investigation" -> "yes";
            default -> throw new AssertionError("Unexpected decision " + decision);
        });

        assertEquals(List.of("dev-work-selection", "dev-task-context-read", "dev-topology-investigation",
                "dev-topology-finding-update", "dev-team-chat-closeout"), actionIds(requests));
    }

    @Test
    void qaCanAuthorBeforeImplementationAndExecuteSeparatelyAfterReview() throws Exception {
        var qa = new QualityEngineerRoutine();
        var work = qa.routine().routeUri("test-engineering");
        var author = run(qa, work, decision -> switch (decision) {
            case "qa-has-test-work" -> "yes";
            case "qa-test-next-step" -> "author";
            default -> throw new AssertionError("Unexpected decision " + decision);
        });
        assertEquals(List.of("qa-test-work-select", "qa-test-engineering-step", "qa-test-evidence-update",
                "qa-awareness"), actionIds(author));

        var review = run(new QualityEngineerRoutine(), "direct:qa-focus-test-review", decision -> {
            throw new AssertionError("Unexpected decision " + decision);
        });
        assertEquals(List.of("qa-test-review", "qa-awareness"), actionIds(review));

        var execute = run(new QualityEngineerRoutine(), work, decision -> switch (decision) {
            case "qa-has-test-work" -> "yes";
            case "qa-test-next-step" -> "execute";
            default -> throw new AssertionError("Unexpected decision " + decision);
        });
        assertEquals(List.of("qa-test-work-select", "qa-test-execution-step", "qa-test-evidence-update",
                "qa-awareness"), actionIds(execute));
    }

    @Test
    void qaConveysTheRetestDecisionToTheDefectUpdate() throws Exception {
        var qa = new QualityEngineerRoutine();
        var requests = run(qa, qa.routine().routeUri("defect-retest"), decision -> {
            assertEquals("qa-defect-fixed", decision);
            return "no";
        });

        assertEquals(List.of("qa-defect-retest-context", "qa-defect-retest-execution", "qa-defect-retest-update",
                "qa-awareness"), actionIds(requests));
        var update = requests.stream().filter(job -> job.passType() == LlmPassType.ACTION)
                .filter(job -> primitiveId(job).equals("qa-defect-retest-update")).findFirst().orElseThrow();
        assertTrue(update.prompt().replaceAll("\\s+", " ").contains("DEFECT FIXED: no"));
    }

    private static List<LlmJobEnvelope> run(RouteBuilder routine, String uri, Function<String, String> decisions) throws Exception {
        var requests = new ArrayList<LlmJobEnvelope>();
        var mapper = new ObjectMapper();
        try (var context = TestCamelContexts.quiet()) {
            context.setAutowiredEnabled(false);
            var template = context.createProducerTemplate();
            context.addService(template);
            var profiles = new ToolProfileRegistry(List.of(DeliveryManagementToolProfile.ID,
                    EngineeringWorkToolProfile.ID, QualityManagementToolProfile.ID,
                    QualitySourceControlWriteToolProfile.ID, SourceControlToolProfile.ID, SourceControlWriteToolProfile.ID,
                    TeamChatToolProfile.ID, WorkspaceReadonlyToolProfile.ID).stream()
                    .map(id -> ToolProfile.builder(id).build()).toList());
            var projections = FrameworkFixtures.projectionManager(FrameworkFixtures.projectionStore());
            context.addComponent("tack", new TackComponent(new PrimitiveExecutor(mapper, template,
                    FrameworkFixtures.personaCatalog(), profiles, projections)));
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:llm").process(exchange -> {
                        FrameworkFixtures.jobFactory().prepare(exchange);
                        var job = exchange.getMessage().getBody(LlmJobEnvelope.class);
                        requests.add(job);
                        String answer = switch (job.passType()) {
                            case ACTION -> "Verified task KAN-14, repository agent-workspace/mission-service and PR 7.";
                            case SYNTHESIS -> mapper.writeValueAsString(Map.of(
                                    "content", "Task KAN-14, repository agent-workspace/mission-service, PR 7.",
                                    "summary", "Scripted evidence.", "outcome", "SUCCEEDED", "durableProgress", false, "confidence", 1));
                            case CONSOLIDATION -> mapper.writeValueAsString(Map.of(
                                    "content", "Task KAN-14, repository agent-workspace/mission-service, PR 7."));
                            case DECISION -> mapper.writeValueAsString(Map.of(
                                    "result", decisions.apply(primitiveId(job)), "confidence", 1, "reason", "Scripted decision."));
                            default -> throw new AssertionError("Unexpected pass " + job.passType());
                        };
                        exchange.getMessage().setBody(LlmJobResult.succeeded(job, "test", answer, answer, Instant.now()));
                    });
                }
            });
            context.addRoutes(routine);
            context.start();
            try (var caller = context.createProducerTemplate()) {
                var properties = FrameworkFixtures.exchangeWithRun(FrameworkFixtures.PERSONA_A, FrameworkFixtures.ROUTINE_A)
                        .getProperties();
                var reply = caller.request(uri, exchange -> properties.forEach(exchange::setProperty));
                assertNull(reply.getException(), () -> String.valueOf(reply.getException()));
                var stack = reply.getProperty(ThoughtStack.PROPERTY, ThoughtStack.class);
                assertNotNull(stack);
                assertTrue(stack.frames().isEmpty());
            }
        }
        return requests;
    }

    private static List<String> actionIds(List<LlmJobEnvelope> requests) {
        return requests.stream().filter(job -> job.passType() == LlmPassType.ACTION)
                .map(SoftwareTeamWorkflowRouteTest::primitiveId).toList();
    }

    private static String primitiveId(LlmJobEnvelope job) {
        return (String) job.metadata().get("primitiveId");
    }
}
