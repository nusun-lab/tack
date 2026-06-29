package us.nusun.llmshare.framework.tests.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.AgentRunProjection;
import us.nusun.llmshare.framework.model.CreateResult;
import us.nusun.llmshare.framework.model.LlmJobRequest;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.model.StartAgentRunCommand;
import us.nusun.llmshare.framework.model.StartAgentRunResult;
import us.nusun.llmshare.framework.model.ThoughtFrame;
import us.nusun.llmshare.framework.projection.AgentRunProjectionManager;
import us.nusun.llmshare.framework.projection.ProjectionStore;
import us.nusun.llmshare.framework.route.MissionRoute;
import us.nusun.llmshare.framework.route.PrimitiveRoute;
import us.nusun.llmshare.framework.routine.PrimitiveSupport;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.ThoughtStack;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunIsolationRouteTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PARENT_PERSONA_ID = "test/parent-persona";
    private static final String CHILD_PERSONA_ID = "test/child-persona";
    private static final String PARENT_ROUTINE_ID = "test/parent-routine";
    private static final String GRANDCHILD_PERSONA_ID = "test/grandchild-persona";
    private static final String PARENT_THOUGHT = "Parent-only thought must stay on the parent run.";
    private static final String PARENT_CREATE_PROMPT = "Parent create prompt must not leak into child.";
    private static final String CHILD_THOUGHT = "Child-only thought must stay on the child run.";
    private static final String GRANDCHILD_THOUGHT = "Grandchild-only thought must stay on the grandchild run.";

    @Test
    void childSuccessDoesNotInheritParentState() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineDefinition childRoutine = childRoutine("test/child-routine", "direct:test-child-routine");
        RoutineCatalog routines = routines(childRoutine);
        FrameworkFixtures.set(manager, "routines", routines);
        FrameworkFixtures.set(manager, "personas", personas());

        MissionRoute missionRoute = missionRoute(manager, routines);
        AgentRun parentRun = parentRun("run-parent");
        Mission mission = mission();
        StartAgentRunCommand childRequest = childRequest(childRoutine.id(), "Implement the first vertical slice.");

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(missionRoute);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:test-child-routine")
                            .process(exchange -> {
                                exchange.setProperty("childRoutineObservedRunId", exchange.getProperty("agentRun", AgentRun.class).id());
                                exchange.setProperty("childInheritedThoughtStack", exchange.getProperty("thoughtStack"));
                                exchange.setProperty("childInheritedCreatePrompt", exchange.getProperty("createPrompt"));
                                exchange.setProperty("childInheritedToolProfile", exchange.getProperty("toolProfile"));
                                exchange.setProperty("childInheritedLatestCreateResult", exchange.getProperty("latestCreateResult"));
                            });
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();

            Exchange result = template.send("direct:start-agent-run", exchange -> {
                seedParentState(exchange, manager, mission, parentRun);
                exchange.getIn().setBody(childRequest);
            });

            Object parentTimeline = result.getProperty("expectedParentProjectionTimeline");
            StartAgentRunResult childResult = result.getIn().getBody(StartAgentRunResult.class);
            assertParentStateRestored(result, parentRun, parentTimeline);
            assertNull(result.getProperty("createdAgentRun"));
            assertNotNull(childResult);
            assertNotEquals(parentRun.id(), childResult.agentRunId());
            assertEquals(parentRun.id(), childResult.parentAgentRunId());
            assertEquals(childResult.agentRunId(), result.getProperty("childRoutineObservedRunId"));
            assertNull(result.getProperty("childInheritedThoughtStack"));
            assertNull(result.getProperty("childInheritedCreatePrompt"));
            assertNull(result.getProperty("childInheritedToolProfile"));
            assertNull(result.getProperty("childInheritedLatestCreateResult"));

            AgentRunProjection childProjection = store.get(childResult.agentRunId());
            assertNotNull(childProjection);
            assertEquals(CHILD_PERSONA_ID, childProjection.persona().get("id"));
            assertEquals(parentRun.id(), childProjection.parentAgentRunId());
            assertEquals(childRoutine.id(), childProjection.routine().get("id"));
            assertEquals(List.of("AGENT_RUN_STARTED", "AGENT_RUN_COMPLETED"), events(childProjection));
            assertFalse(projectionContains(childProjection, PARENT_THOUGHT));
            assertFalse(projectionContains(childProjection, PARENT_CREATE_PROMPT));
            assertFalse(events(childProjection).contains("PARENT_EVENT"));

            manager.record(result, "PARENT_AFTER_CHILD");
            AgentRunProjection parentProjection = store.get(parentRun.id());
            assertEquals(List.of("PARENT_EVENT", "PARENT_AFTER_CHILD"), events(parentProjection));
            assertNotEquals(parentProjection.agentRunId(), childProjection.agentRunId());
        }
    }

    @Test
    void childRoutineFailureRestoresParentStateAndRecordsFailureOnly() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineDefinition childRoutine = childRoutine("test/failing-child-routine", "direct:test-failing-child-routine");
        RoutineCatalog routines = routines(childRoutine);
        FrameworkFixtures.set(manager, "routines", routines);
        FrameworkFixtures.set(manager, "personas", personas());

        MissionRoute missionRoute = missionRoute(manager, routines);
        AgentRun parentRun = parentRun("run-parent-failure");
        Mission mission = mission();
        StartAgentRunCommand childRequest = childRequest(childRoutine.id(), "Exercise failure isolation.");

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(missionRoute);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(noErrorHandler());
                    from("direct:test-failing-child-routine")
                            .process(exchange -> exchange.setProperty("childRoutineObservedRunId", exchange.getProperty("agentRun", AgentRun.class).id()))
                            .throwException(new IllegalStateException("child route failed"));
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            Exchange exchange = new DefaultExchange(context);
            seedParentState(exchange, manager, mission, parentRun);
            Object parentTimeline = exchange.getProperty("expectedParentProjectionTimeline");
            exchange.getIn().setBody(childRequest);

            template.send("direct:start-agent-run", exchange);

            StartAgentRunResult childResult = exchange.getIn().getBody(StartAgentRunResult.class);
            assertParentStateRestored(exchange, parentRun, parentTimeline);
            assertNull(exchange.getProperty("createdAgentRun"));
            assertNotNull(childResult);
            assertEquals(parentRun.id(), childResult.parentAgentRunId());
            assertEquals("failed", childResult.status());
            assertEquals(childResult.agentRunId(), exchange.getProperty("childRoutineObservedRunId"));

            AgentRunProjection childProjection = store.get(childResult.agentRunId());
            assertNotNull(childProjection);
            assertEquals(parentRun.id(), childProjection.parentAgentRunId());
            assertEquals(List.of("AGENT_RUN_STARTED", "AGENT_RUN_FAILED"), events(childProjection));
            assertFalse(events(childProjection).contains("AGENT_RUN_COMPLETED"));
            assertFalse(events(childProjection).contains("PARENT_EVENT"));
            assertFalse(projectionContains(childProjection, PARENT_THOUGHT));
            assertFalse(projectionContains(childProjection, PARENT_CREATE_PROMPT));

            manager.record(exchange, "PARENT_AFTER_CHILD_FAILURE");
            AgentRunProjection parentProjection = store.get(parentRun.id());
            assertEquals(List.of("PARENT_EVENT", "PARENT_AFTER_CHILD_FAILURE"), events(parentProjection));
            assertFalse(events(parentProjection).contains("AGENT_RUN_FAILED"));
            assertFalse(events(parentProjection).contains("AGENT_RUN_COMPLETED"));
        }
    }

    @Test
    void childSetupFailureRestoresParentStateAndLeavesNoActiveChildScope() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineDefinition childRoutine = childRoutine("test/setup-child-routine", "direct:test-setup-child-routine");
        RoutineCatalog routines = routines(childRoutine);
        FrameworkFixtures.set(manager, "routines", routines);
        FrameworkFixtures.set(manager, "personas", personas());

        MissionRoute missionRoute = missionRoute(manager, routines);
        AgentRun parentRun = parentRun("run-parent-setup-failure");
        Mission mission = mission();
        StartAgentRunCommand invalidChildRequest = new StartAgentRunCommand(
                "mission-1",
                "missing/persona",
                childRoutine.id(),
                null,
                "Invalid persona should fail before child route invocation.",
                Map.of());

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(missionRoute);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:test-setup-child-routine")
                            .process(exchange -> exchange.setProperty("childRoutineInvoked", true));
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            Exchange exchange = new DefaultExchange(context);
            seedParentState(exchange, manager, mission, parentRun);
            Object parentTimeline = exchange.getProperty("expectedParentProjectionTimeline");
            exchange.getIn().setBody(invalidChildRequest);

            try {
                template.send("direct:start-agent-run", exchange);
            } catch (Exception ignored) {
                // State restoration is the contract under test; the setup error may propagate.
            }

            assertParentStateRestored(exchange, parentRun, parentTimeline);
            assertNull(exchange.getProperty("createdAgentRun"));
            assertNull(exchange.getProperty("childRoutineInvoked"));
            assertFalse(exchange.getProperties().containsKey("childAgentRunSetupComplete"));
            assertFalse(exchange.getProperties().containsKey("childAgentRunFailure"));
            assertFalse(exchange.getProperties().containsKey("agentRunExchangeScopeStack"));
            assertEquals(List.of("PARENT_EVENT"), events(store.get(parentRun.id())));
            assertEquals(1, store.all().size());
        }
    }

    @Test
    void invalidCommandBodySetupFailureDoesNotFailParentRun() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineDefinition childRoutine = childRoutine("test/invalid-body-child-routine", "direct:test-invalid-body-child-routine");
        RoutineCatalog routines = routines(childRoutine);
        FrameworkFixtures.set(manager, "routines", routines);
        FrameworkFixtures.set(manager, "personas", personas());

        MissionRoute missionRoute = missionRoute(manager, routines);
        AgentRun parentRun = parentRun("run-parent-invalid-body");
        Mission mission = mission();

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(missionRoute);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:test-invalid-body-child-routine")
                            .process(exchange -> exchange.setProperty("invalidBodyChildInvoked", true));
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            Exchange exchange = new DefaultExchange(context);
            seedParentState(exchange, manager, mission, parentRun);
            Object parentTimeline = exchange.getProperty("expectedParentProjectionTimeline");
            exchange.getIn().setBody("not a StartAgentRunCommand");

            try {
                template.send("direct:start-agent-run", exchange);
            } catch (Exception ignored) {
                // The invalid command may propagate; isolation is the contract under test.
            }

            assertParentStateRestored(exchange, parentRun, parentTimeline);
            assertEquals("active", parentRun.status());
            assertNull(exchange.getProperty("createdAgentRun"));
            assertNull(exchange.getProperty("invalidBodyChildInvoked"));
            assertFalse(exchange.getProperties().containsKey("agentRunExchangeScopeStack"));
            assertFalse(exchange.getProperties().containsKey("childAgentRunSetupComplete"));
            assertFalse(exchange.getProperties().containsKey("childAgentRunFailure"));

            AgentRunProjection parentProjection = store.get(parentRun.id());
            assertEquals(List.of("PARENT_EVENT"), events(parentProjection));
            assertFalse(events(parentProjection).contains("AGENT_RUN_FAILED"));
            assertEquals(1, store.all().size());
        }
    }

    @Test
    void childPromptAndProjectionDoNotSeeParentThought() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineDefinition childRoutine = childRoutine("test/prompt-child-routine", "direct:test-prompt-child-routine");
        RoutineCatalog routines = routines(childRoutine);
        FrameworkFixtures.set(manager, "routines", routines);
        FrameworkFixtures.set(manager, "personas", personas());

        MissionRoute missionRoute = missionRoute(manager, routines);
        PrimitiveSupport primitiveSupport = FrameworkFixtures.primitiveSupport();
        FrameworkFixtures.set(primitiveSupport, "personas", personas());
        FrameworkFixtures.set(primitiveSupport, "routines", routines);
        FrameworkFixtures.set(primitiveSupport, "projections", manager);
        PrimitiveRoute primitiveRoute = new PrimitiveRoute();
        FrameworkFixtures.set(primitiveRoute, "primitive", primitiveSupport);

        AgentRun parentRun = parentRun("run-parent-prompt");
        Mission mission = mission();
        StartAgentRunCommand childRequest = childRequest(childRoutine.id(), "Capture child create prompt.");
        List<LlmJobRequest> capturedRequests = new ArrayList<>();

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(missionRoute);
            context.addRoutes(primitiveRoute);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:test-prompt-child-routine")
                            .process(exchange -> {
                                ThoughtStack childStack = new ThoughtStack();
                                childStack.push(new ThoughtFrame("child-thought", "Child context", 200, "Child-only thought content."));
                                exchange.setProperty("thoughtStack", childStack);
                                exchange.setProperty("createId", "child-create");
                                exchange.setProperty("createPrompt", "Child create prompt.");
                                exchange.setProperty("createSchemaJson", "{\"type\":\"object\"}");
                            })
                            .to("direct:create");

                    from("direct:llm")
                            .process(exchange -> {
                                LlmJobRequest request = exchange.getIn().getBody(LlmJobRequest.class);
                                capturedRequests.add(request);
                                exchange.getIn().setBody(FrameworkFixtures.llmResult(request.requester(), "{}"));
                            });
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            Exchange exchange = new DefaultExchange(context);
            seedParentState(exchange, manager, mission, parentRun);
            Object parentTimeline = exchange.getProperty("expectedParentProjectionTimeline");
            exchange.getIn().setBody(childRequest);

            template.send("direct:start-agent-run", exchange);

            StartAgentRunResult childResult = exchange.getIn().getBody(StartAgentRunResult.class);
            assertParentStateRestored(exchange, parentRun, parentTimeline);
            assertNull(exchange.getProperty("createdAgentRun"));
            assertNotNull(childResult);
            assertEquals(1, capturedRequests.size());
            String prompt = capturedRequests.getFirst().prompt();
            assertFalse(prompt.contains(PARENT_THOUGHT));
            assertFalse(prompt.contains(PARENT_CREATE_PROMPT));
            assertFalse(prompt.contains("Parent-only"));
            assertFalse(prompt.contains("Parent create"));
            assertFalse(prompt.contains("Available personas"));
            assertFalse(prompt.contains("Available routines"));
            assertTrue(prompt.contains("Child create prompt."));
            assertTrue(prompt.contains("Child-only thought content."));

            AgentRunProjection childProjection = store.get(childResult.agentRunId());
            assertNotNull(childProjection);
            assertFalse(projectionContains(childProjection, PARENT_THOUGHT));
            assertFalse(projectionContains(childProjection, PARENT_CREATE_PROMPT));
            assertEquals(List.of("AGENT_RUN_STARTED", "CREATE_STARTED", "SYNTHESIS_PASS_QUEUED", "SYNTHESIS_PASS_COMPLETED", "CREATE_COMPLETED", "AGENT_RUN_COMPLETED"), events(childProjection));
        }
    }

    @Test
    void nestedChildAndGrandchildSuccessRestoreEachScope() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineDefinition childRoutine = childRoutine("test/nested-child-routine", "direct:test-nested-child-routine");
        RoutineDefinition grandchildRoutine = childRoutine("test/nested-grandchild-routine", "direct:test-nested-grandchild-routine");
        RoutineCatalog routines = routines(childRoutine, grandchildRoutine);
        FrameworkFixtures.set(manager, "routines", routines);
        FrameworkFixtures.set(manager, "personas", personas());

        MissionRoute missionRoute = missionRoute(manager, routines);
        AgentRun parentRun = parentRun("run-parent-nested");
        Mission mission = mission();
        StartAgentRunCommand childRequest = childRequest(childRoutine.id(), "Start nested child.");

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(missionRoute);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:test-nested-child-routine")
                            .process(exchange -> {
                                AgentRun childRun = exchange.getProperty("agentRun", AgentRun.class);
                                exchange.setProperty("observedChildRunId", childRun.id());
                                exchange.setProperty("observedChildProjectionTimeline", exchange.getProperty("projectionTimeline"));
                                seedChildState(exchange, "child-create-prompt");
                                manager.record(exchange, "CHILD_EVENT");
                                exchange.getIn().setBody(new StartAgentRunCommand(
                                        "mission-1",
                                        GRANDCHILD_PERSONA_ID,
                                        grandchildRoutine.id(),
                                        null,
                                        "Start nested grandchild.",
                                        Map.of()));
                            })
                            .to("direct:start-agent-run")
                            .process(exchange -> {
                                StartAgentRunResult grandchild = exchange.getIn().getBody(StartAgentRunResult.class);
                                exchange.setProperty("observedGrandchildRunId", grandchild.agentRunId());
                                assertEquals("complete", grandchild.status());
                                assertChildStateRestored(exchange, "child-create-prompt");
                                assertSame(exchange.getProperty("observedChildProjectionTimeline"), exchange.getProperty("projectionTimeline"));
                                manager.record(exchange, "CHILD_AFTER_GRANDCHILD");
                            });

                    from("direct:test-nested-grandchild-routine")
                            .process(exchange -> {
                                exchange.setProperty("grandchildInheritedThoughtStack", exchange.getProperty("thoughtStack"));
                                exchange.setProperty("grandchildInheritedActPrompt", exchange.getProperty("actPrompt"));
                                exchange.setProperty("grandchildInheritedDecisionPrompt", exchange.getProperty("decisionPrompt"));
                                exchange.setProperty("grandchildInheritedCreatePrompt", exchange.getProperty("createPrompt"));
                                ThoughtStack grandchildStack = new ThoughtStack();
                                grandchildStack.push(new ThoughtFrame("grandchild", "Grandchild context", 200, GRANDCHILD_THOUGHT));
                                exchange.setProperty("thoughtStack", grandchildStack);
                                manager.record(exchange, "GRANDCHILD_EVENT");
                            });
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            Exchange exchange = new DefaultExchange(context);
            seedParentState(exchange, manager, mission, parentRun);
            Object parentTimeline = exchange.getProperty("expectedParentProjectionTimeline");
            exchange.getIn().setBody(childRequest);

            template.send("direct:start-agent-run", exchange);

            StartAgentRunResult childResult = exchange.getIn().getBody(StartAgentRunResult.class);
            assertParentStateRestored(exchange, parentRun, parentTimeline);
            assertNull(exchange.getProperty("createdAgentRun"));
            assertEquals("complete", childResult.status());
            assertEquals(childResult.agentRunId(), exchange.getProperty("observedChildRunId"));
            assertNull(exchange.getProperty("grandchildInheritedThoughtStack"));
            assertNull(exchange.getProperty("grandchildInheritedActPrompt"));
            assertNull(exchange.getProperty("grandchildInheritedDecisionPrompt"));
            assertNull(exchange.getProperty("grandchildInheritedCreatePrompt"));
            assertFalse(exchange.getProperties().containsKey("agentRunExchangeScopeStack"));

            AgentRunProjection parentProjection = store.get(parentRun.id());
            AgentRunProjection childProjection = store.get(childResult.agentRunId());
            AgentRunProjection grandchildProjection = store.get(exchange.getProperty("observedGrandchildRunId", String.class));
            assertNotNull(parentProjection);
            assertNotNull(childProjection);
            assertNotNull(grandchildProjection);
            assertNotEquals(parentProjection.agentRunId(), childProjection.agentRunId());
            assertNotEquals(childProjection.agentRunId(), grandchildProjection.agentRunId());
            assertEquals(List.of("PARENT_EVENT"), events(parentProjection));
            assertEquals(List.of("AGENT_RUN_STARTED", "CHILD_EVENT", "CHILD_AFTER_GRANDCHILD", "AGENT_RUN_COMPLETED"), events(childProjection));
            assertEquals(List.of("AGENT_RUN_STARTED", "GRANDCHILD_EVENT", "AGENT_RUN_COMPLETED"), events(grandchildProjection));
            assertFalse(projectionContains(grandchildProjection, PARENT_THOUGHT));
            assertFalse(projectionContains(grandchildProjection, CHILD_THOUGHT));
            assertFalse(projectionContains(childProjection, PARENT_THOUGHT));
            assertFalse(projectionContains(childProjection, GRANDCHILD_THOUGHT));
        }
    }

    @Test
    void nestedGrandchildRoutineFailureRestoresChildAndParentScopes() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineDefinition childRoutine = childRoutine("test/nested-child-failure-routine", "direct:test-nested-child-failure-routine");
        RoutineDefinition grandchildRoutine = childRoutine("test/nested-grandchild-failure-routine", "direct:test-nested-grandchild-failure-routine");
        RoutineCatalog routines = routines(childRoutine, grandchildRoutine);
        FrameworkFixtures.set(manager, "routines", routines);
        FrameworkFixtures.set(manager, "personas", personas());

        MissionRoute missionRoute = missionRoute(manager, routines);
        AgentRun parentRun = parentRun("run-parent-nested-failure");
        Mission mission = mission();
        StartAgentRunCommand childRequest = childRequest(childRoutine.id(), "Start nested child with failing grandchild.");

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(missionRoute);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(noErrorHandler());
                    from("direct:test-nested-child-failure-routine")
                            .process(exchange -> {
                                exchange.setProperty("observedChildRunId", exchange.getProperty("agentRun", AgentRun.class).id());
                                exchange.setProperty("observedChildProjectionTimeline", exchange.getProperty("projectionTimeline"));
                                seedChildState(exchange, "child-failure-create-prompt");
                                manager.record(exchange, "CHILD_EVENT");
                                exchange.getIn().setBody(new StartAgentRunCommand(
                                        "mission-1",
                                        GRANDCHILD_PERSONA_ID,
                                        grandchildRoutine.id(),
                                        null,
                                        "Start failing grandchild.",
                                        Map.of()));
                            })
                            .to("direct:start-agent-run")
                            .process(exchange -> {
                                StartAgentRunResult grandchild = exchange.getIn().getBody(StartAgentRunResult.class);
                                exchange.setProperty("observedGrandchildRunId", grandchild.agentRunId());
                                assertEquals("failed", grandchild.status());
                                assertChildStateRestored(exchange, "child-failure-create-prompt");
                                assertSame(exchange.getProperty("observedChildProjectionTimeline"), exchange.getProperty("projectionTimeline"));
                                manager.record(exchange, "CHILD_AFTER_GRANDCHILD_FAILURE");
                            });

                    from("direct:test-nested-grandchild-failure-routine")
                            .process(exchange -> {
                                ThoughtStack grandchildStack = new ThoughtStack();
                                grandchildStack.push(new ThoughtFrame("grandchild", "Grandchild context", 200, GRANDCHILD_THOUGHT));
                                exchange.setProperty("thoughtStack", grandchildStack);
                                manager.record(exchange, "GRANDCHILD_EVENT");
                            })
                            .throwException(new IllegalStateException("grandchild route failed"));
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            Exchange exchange = new DefaultExchange(context);
            seedParentState(exchange, manager, mission, parentRun);
            Object parentTimeline = exchange.getProperty("expectedParentProjectionTimeline");
            exchange.getIn().setBody(childRequest);

            template.send("direct:start-agent-run", exchange);

            StartAgentRunResult childResult = exchange.getIn().getBody(StartAgentRunResult.class);
            assertParentStateRestored(exchange, parentRun, parentTimeline);
            assertNull(exchange.getProperty("createdAgentRun"));
            assertEquals("complete", childResult.status());
            assertFalse(exchange.getProperties().containsKey("agentRunExchangeScopeStack"));

            AgentRunProjection parentProjection = store.get(parentRun.id());
            AgentRunProjection childProjection = store.get(childResult.agentRunId());
            AgentRunProjection grandchildProjection = store.get(exchange.getProperty("observedGrandchildRunId", String.class));
            assertEquals(List.of("PARENT_EVENT"), events(parentProjection));
            assertEquals(List.of("AGENT_RUN_STARTED", "CHILD_EVENT", "CHILD_AFTER_GRANDCHILD_FAILURE", "AGENT_RUN_COMPLETED"), events(childProjection));
            assertEquals(List.of("AGENT_RUN_STARTED", "GRANDCHILD_EVENT", "AGENT_RUN_FAILED"), events(grandchildProjection));
            assertFalse(events(grandchildProjection).contains("AGENT_RUN_COMPLETED"));
            assertFalse(projectionContains(childProjection, GRANDCHILD_THOUGHT));
            assertFalse(projectionContains(parentProjection, CHILD_THOUGHT));
            assertFalse(projectionContains(parentProjection, GRANDCHILD_THOUGHT));
        }
    }

    @Test
    void nestedGrandchildSetupFailureRestoresChildAndParentScopes() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineDefinition childRoutine = childRoutine("test/nested-child-setup-failure-routine", "direct:test-nested-child-setup-failure-routine");
        RoutineDefinition grandchildRoutine = childRoutine("test/nested-grandchild-setup-failure-routine", "direct:test-nested-grandchild-setup-failure-routine");
        RoutineCatalog routines = routines(childRoutine, grandchildRoutine);
        FrameworkFixtures.set(manager, "routines", routines);
        FrameworkFixtures.set(manager, "personas", personas());

        MissionRoute missionRoute = missionRoute(manager, routines);
        AgentRun parentRun = parentRun("run-parent-nested-setup-failure");
        Mission mission = mission();
        StartAgentRunCommand childRequest = childRequest(childRoutine.id(), "Start nested child with setup-failing grandchild.");

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(missionRoute);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:test-nested-child-setup-failure-routine")
                            .process(exchange -> {
                                exchange.setProperty("observedChildRunId", exchange.getProperty("agentRun", AgentRun.class).id());
                                exchange.setProperty("observedChildProjectionTimeline", exchange.getProperty("projectionTimeline"));
                                seedChildState(exchange, "child-setup-failure-create-prompt");
                                manager.record(exchange, "CHILD_EVENT");
                                exchange.getIn().setBody(new StartAgentRunCommand(
                                        "mission-1",
                                        "missing/grandchild-persona",
                                        grandchildRoutine.id(),
                                        null,
                                        "Grandchild setup must fail before route invocation.",
                                        Map.of()));
                            })
                            .doTry()
                                .to("direct:start-agent-run")
                            .doCatch(Exception.class)
                                .process(exchange -> {
                                    exchange.setProperty("grandchildSetupFailureCaught", true);
                                    assertChildStateRestored(exchange, "child-setup-failure-create-prompt");
                                    assertSame(exchange.getProperty("observedChildProjectionTimeline"), exchange.getProperty("projectionTimeline"));
                                    assertEquals(exchange.getProperty("observedChildRunId"), exchange.getProperty("createdAgentRun", AgentRun.class).id());
                                    assertNull(exchange.getProperty("grandchildRouteInvoked"));
                                    manager.record(exchange, "CHILD_AFTER_GRANDCHILD_SETUP_FAILURE");
                                })
                            .end();

                    from("direct:test-nested-grandchild-setup-failure-routine")
                            .process(exchange -> exchange.setProperty("grandchildRouteInvoked", true));
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            Exchange exchange = new DefaultExchange(context);
            seedParentState(exchange, manager, mission, parentRun);
            Object parentTimeline = exchange.getProperty("expectedParentProjectionTimeline");
            exchange.getIn().setBody(childRequest);

            template.send("direct:start-agent-run", exchange);

            StartAgentRunResult childResult = exchange.getIn().getBody(StartAgentRunResult.class);
            assertParentStateRestored(exchange, parentRun, parentTimeline);
            assertNull(exchange.getProperty("createdAgentRun"));
            assertEquals("complete", childResult.status());
            assertEquals(Boolean.TRUE, exchange.getProperty("grandchildSetupFailureCaught"));
            assertFalse(exchange.getProperties().containsKey("agentRunExchangeScopeStack"));
            assertNull(exchange.getProperty("grandchildRouteInvoked"));

            AgentRunProjection parentProjection = store.get(parentRun.id());
            AgentRunProjection childProjection = store.get(childResult.agentRunId());
            assertEquals(List.of("PARENT_EVENT"), events(parentProjection));
            assertEquals(List.of("AGENT_RUN_STARTED", "CHILD_EVENT", "CHILD_AFTER_GRANDCHILD_SETUP_FAILURE", "AGENT_RUN_COMPLETED"), events(childProjection));
            assertEquals(2, store.all().size());
        }
    }

    @Test
    void nestedInvalidGrandchildCommandRestoresChildAndParentScopes() throws Exception {
        ProjectionStore store = FrameworkFixtures.projectionStore();
        AgentRunProjectionManager manager = FrameworkFixtures.projectionManager(store);
        RoutineDefinition childRoutine = childRoutine("test/nested-child-invalid-command-routine", "direct:test-nested-child-invalid-command-routine");
        RoutineDefinition grandchildRoutine = childRoutine("test/nested-grandchild-invalid-command-routine", "direct:test-nested-grandchild-invalid-command-routine");
        RoutineCatalog routines = routines(childRoutine, grandchildRoutine);
        FrameworkFixtures.set(manager, "routines", routines);
        FrameworkFixtures.set(manager, "personas", personas());

        MissionRoute missionRoute = missionRoute(manager, routines);
        AgentRun parentRun = parentRun("run-parent-nested-invalid-command");
        Mission mission = mission();
        StartAgentRunCommand childRequest = childRequest(childRoutine.id(), "Start nested child with invalid grandchild command.");

        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.addRoutes(missionRoute);
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:test-nested-child-invalid-command-routine")
                            .process(exchange -> {
                                exchange.setProperty("observedChildRunId", exchange.getProperty("agentRun", AgentRun.class).id());
                                exchange.setProperty("observedChildProjectionTimeline", exchange.getProperty("projectionTimeline"));
                                seedChildState(exchange, "child-invalid-command-create-prompt");
                                manager.record(exchange, "CHILD_EVENT");
                                exchange.getIn().setBody("not a grandchild StartAgentRunCommand");
                            })
                            .doTry()
                                .to("direct:start-agent-run")
                            .doCatch(Exception.class)
                                .process(exchange -> {
                                    exchange.setProperty("grandchildInvalidCommandCaught", true);
                                    assertChildStateRestored(exchange, "child-invalid-command-create-prompt");
                                    assertSame(exchange.getProperty("observedChildProjectionTimeline"), exchange.getProperty("projectionTimeline"));
                                    assertEquals(exchange.getProperty("observedChildRunId"), exchange.getProperty("createdAgentRun", AgentRun.class).id());
                                    assertNull(exchange.getProperty("grandchildInvalidCommandRouteInvoked"));
                                    manager.record(exchange, "CHILD_AFTER_GRANDCHILD_INVALID_COMMAND");
                                })
                            .end();

                    from("direct:test-nested-grandchild-invalid-command-routine")
                            .process(exchange -> exchange.setProperty("grandchildInvalidCommandRouteInvoked", true));
                }
            });
            context.start();
            ProducerTemplate template = context.createProducerTemplate();
            Exchange exchange = new DefaultExchange(context);
            seedParentState(exchange, manager, mission, parentRun);
            Object parentTimeline = exchange.getProperty("expectedParentProjectionTimeline");
            exchange.getIn().setBody(childRequest);

            template.send("direct:start-agent-run", exchange);

            StartAgentRunResult childResult = exchange.getIn().getBody(StartAgentRunResult.class);
            assertParentStateRestored(exchange, parentRun, parentTimeline);
            assertNull(exchange.getProperty("createdAgentRun"));
            assertEquals("complete", childResult.status());
            assertEquals(Boolean.TRUE, exchange.getProperty("grandchildInvalidCommandCaught"));
            assertFalse(exchange.getProperties().containsKey("agentRunExchangeScopeStack"));
            assertNull(exchange.getProperty("grandchildInvalidCommandRouteInvoked"));

            AgentRunProjection parentProjection = store.get(parentRun.id());
            AgentRunProjection childProjection = store.get(childResult.agentRunId());
            assertEquals(List.of("PARENT_EVENT"), events(parentProjection));
            assertEquals(List.of("AGENT_RUN_STARTED", "CHILD_EVENT", "CHILD_AFTER_GRANDCHILD_INVALID_COMMAND", "AGENT_RUN_COMPLETED"), events(childProjection));
            assertFalse(events(parentProjection).contains("AGENT_RUN_FAILED"));
            assertFalse(events(childProjection).contains("AGENT_RUN_FAILED"));
            assertEquals(2, store.all().size());
        }
    }

    private static us.nusun.llmshare.framework.inventory.PersonaCatalog personas() {
        return new us.nusun.llmshare.framework.inventory.PersonaCatalog(List.of(
                Persona.builder(PARENT_PERSONA_ID)
                        .name("Parent Persona")
                        .description("Generic parent persona for framework route tests.")
                        .label("PAR")
                        .color("38;2;200;200;200")
                        .build(),
                Persona.builder(CHILD_PERSONA_ID)
                        .name("Child Persona")
                        .description("Generic child persona for framework route tests.")
                        .label("CHI")
                        .color("38;2;150;200;250")
                        .build(),
                Persona.builder(GRANDCHILD_PERSONA_ID)
                        .name("Grandchild Persona")
                        .description("Generic grandchild persona for framework route tests.")
                        .label("GCH")
                        .color("38;2;150;250;200")
                        .build()));
    }

    private static MissionRoute missionRoute(AgentRunProjectionManager manager, RoutineCatalog routines) {
        MissionRoute missionRoute = new MissionRoute();
        FrameworkFixtures.set(missionRoute, "personas", personas());
        FrameworkFixtures.set(missionRoute, "routines", routines);
        FrameworkFixtures.set(missionRoute, "projections", manager);
        FrameworkFixtures.set(missionRoute, "objectMapper", new ObjectMapper());
        return missionRoute;
    }

    private static RoutineCatalog routines(RoutineDefinition... childRoutines) {
        ArrayList<RoutineDefinition> definitions = new ArrayList<>();
        definitions.add(parentRoutine());
        definitions.addAll(List.of(childRoutines));
        return new RoutineCatalog(definitions);
    }

    private static RoutineDefinition childRoutine(String id, String routeUri) {
        return RoutineDefinition.builder(id)
                .name("Child Routine")
                .description("A child routine for route state isolation.")
                .routeUri(routeUri)
                .build();
    }

    private static RoutineDefinition parentRoutine() {
        return RoutineDefinition.builder(PARENT_ROUTINE_ID)
                .name("Parent Routine")
                .description("A generic parent routine for AgentRun route tests.")
                .routeUri("direct:test-parent-routine")
                .build();
    }

    private static AgentRun parentRun(String id) {
        return new AgentRun(id, "mission-1", PARENT_PERSONA_ID, PARENT_ROUTINE_ID, Map.of());
    }

    private static Mission mission() {
        return new Mission("mission-1", "Build a parking app", List.of(), "active", Map.of(), Instant.now());
    }

    private static StartAgentRunCommand childRequest(String childRoutineId, String reason) {
        return new StartAgentRunCommand("mission-1", CHILD_PERSONA_ID, childRoutineId, null, reason, Map.of());
    }

    private static void seedParentState(Exchange exchange, AgentRunProjectionManager manager, Mission mission, AgentRun parentRun) {
        exchange.setProperty("mission", mission);
        exchange.setProperty("agentRun", parentRun);
        ThoughtStack parentStack = new ThoughtStack();
        parentStack.push(new ThoughtFrame("parent-thought", "Parent context", 200, PARENT_THOUGHT));
        exchange.setProperty("thoughtStack", parentStack);
        exchange.setProperty("routineThoughtScopeNames", new ArrayList<>(List.of("parent")));
        exchange.setProperty("actId", "parent-act");
        exchange.setProperty("actPrompt", "Parent act prompt must not leak into child.");
        exchange.setProperty("decisionId", "parent-decision");
        exchange.setProperty("decisionPrompt", "Parent decision prompt must not leak into child.");
        exchange.setProperty("createId", "parent-create");
        exchange.setProperty("createPrompt", PARENT_CREATE_PROMPT);
        exchange.setProperty("createSchema", "parent schema");
        exchange.setProperty("createSchemaJson", "{\"type\":\"object\"}");
        exchange.setProperty("createSchemaUri", "classpath:schemas/parent.json");
        exchange.setProperty("schemaId", "parent-schema");
        exchange.setProperty("toolProfile", "team-chat");
        exchange.setProperty("toolRuntime", "tool-high");
        exchange.setProperty("synthesisRuntime", "synthesis-high");
        exchange.setProperty("resolvedToolRuntime", "tool-high");
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-high");
        exchange.setProperty("toolPassResult", "parent tool pass");
        exchange.setProperty("latestPrimitiveFailure", "parent primitive failure");
        exchange.setProperty("latestStepResult", "parent latest step");
        exchange.setProperty("latestDecisionResult", "parent latest decision");
        exchange.setProperty("latestCreateResult", new CreateResult("parent-create", "parent-schema", MAPPER.createObjectNode().put("parent", true)));
        manager.record(exchange, "PARENT_EVENT");
        exchange.setProperty("expectedParentProjectionTimeline", exchange.getProperty("projectionTimeline"));
    }

    private static void assertParentStateRestored(Exchange exchange, AgentRun parentRun, Object parentTimeline) {
        assertSame(parentRun, exchange.getProperty("agentRun", AgentRun.class));
        assertSame(parentTimeline, exchange.getProperty("projectionTimeline"));
        assertEquals(PARENT_THOUGHT, exchange.getProperty("thoughtStack", ThoughtStack.class).frames().getFirst().content());
        assertEquals(List.of("parent"), exchange.getProperty("routineThoughtScopeNames"));
        assertEquals("parent-act", exchange.getProperty("actId"));
        assertEquals("Parent act prompt must not leak into child.", exchange.getProperty("actPrompt"));
        assertEquals("parent-decision", exchange.getProperty("decisionId"));
        assertEquals("Parent decision prompt must not leak into child.", exchange.getProperty("decisionPrompt"));
        assertEquals("parent-create", exchange.getProperty("createId"));
        assertEquals(PARENT_CREATE_PROMPT, exchange.getProperty("createPrompt"));
        assertEquals("parent schema", exchange.getProperty("createSchema"));
        assertEquals("{\"type\":\"object\"}", exchange.getProperty("createSchemaJson"));
        assertEquals("classpath:schemas/parent.json", exchange.getProperty("createSchemaUri"));
        assertEquals("parent-schema", exchange.getProperty("schemaId"));
        assertEquals("team-chat", exchange.getProperty("toolProfile"));
        assertEquals("tool-high", exchange.getProperty("toolRuntime"));
        assertEquals("synthesis-high", exchange.getProperty("synthesisRuntime"));
        assertEquals("tool-high", exchange.getProperty("resolvedToolRuntime"));
        assertEquals("synthesis-high", exchange.getProperty("resolvedSynthesisRuntime"));
        assertEquals("parent tool pass", exchange.getProperty("toolPassResult"));
        assertEquals("parent primitive failure", exchange.getProperty("latestPrimitiveFailure"));
        assertEquals("parent latest step", exchange.getProperty("latestStepResult"));
        assertEquals("parent latest decision", exchange.getProperty("latestDecisionResult"));
        assertEquals("parent-create", exchange.getProperty("latestCreateResult", CreateResult.class).createId());
        assertFalse(exchange.getProperties().containsKey("parentAgentRun"));
        assertFalse(exchange.getProperties().containsKey("parentProjectionTimeline"));
    }

    private static void seedChildState(Exchange exchange, String createPrompt) {
        ThoughtStack childStack = new ThoughtStack();
        childStack.push(new ThoughtFrame("child-thought", "Child context", 200, CHILD_THOUGHT));
        exchange.setProperty("thoughtStack", childStack);
        exchange.setProperty("routineThoughtScopeNames", new ArrayList<>(List.of("child")));
        exchange.setProperty("actId", "child-act");
        exchange.setProperty("actPrompt", "Child act prompt must not leak into grandchild.");
        exchange.setProperty("decisionId", "child-decision");
        exchange.setProperty("decisionPrompt", "Child decision prompt must not leak into grandchild.");
        exchange.setProperty("createId", "child-create");
        exchange.setProperty("createPrompt", createPrompt);
        exchange.setProperty("createSchema", "child schema");
        exchange.setProperty("createSchemaJson", "{\"type\":\"object\",\"properties\":{\"child\":{\"type\":\"boolean\"}}}");
        exchange.setProperty("createSchemaUri", "classpath:schemas/child.json");
        exchange.setProperty("schemaId", "child-schema");
        exchange.setProperty("toolProfile", "team-chat");
        exchange.setProperty("toolRuntime", "tool-high");
        exchange.setProperty("synthesisRuntime", "synthesis-high");
        exchange.setProperty("resolvedToolRuntime", "tool-high");
        exchange.setProperty("resolvedSynthesisRuntime", "synthesis-high");
        exchange.setProperty("toolPassResult", "child tool pass");
        exchange.setProperty("latestPrimitiveFailure", "child primitive failure");
        exchange.setProperty("latestStepResult", "child latest step");
        exchange.setProperty("latestDecisionResult", "child latest decision");
        exchange.setProperty("latestCreateResult", new CreateResult("child-create", "child-schema", MAPPER.createObjectNode().put("child", true)));
    }

    private static void assertChildStateRestored(Exchange exchange, String createPrompt) {
        assertEquals(CHILD_THOUGHT, exchange.getProperty("thoughtStack", ThoughtStack.class).frames().getFirst().content());
        assertEquals(List.of("child"), exchange.getProperty("routineThoughtScopeNames"));
        assertEquals("child-act", exchange.getProperty("actId"));
        assertEquals("Child act prompt must not leak into grandchild.", exchange.getProperty("actPrompt"));
        assertEquals("child-decision", exchange.getProperty("decisionId"));
        assertEquals("Child decision prompt must not leak into grandchild.", exchange.getProperty("decisionPrompt"));
        assertEquals("child-create", exchange.getProperty("createId"));
        assertEquals(createPrompt, exchange.getProperty("createPrompt"));
        assertEquals("child schema", exchange.getProperty("createSchema"));
        assertEquals("{\"type\":\"object\",\"properties\":{\"child\":{\"type\":\"boolean\"}}}", exchange.getProperty("createSchemaJson"));
        assertEquals("classpath:schemas/child.json", exchange.getProperty("createSchemaUri"));
        assertEquals("child-schema", exchange.getProperty("schemaId"));
        assertEquals("team-chat", exchange.getProperty("toolProfile"));
        assertEquals("tool-high", exchange.getProperty("toolRuntime"));
        assertEquals("synthesis-high", exchange.getProperty("synthesisRuntime"));
        assertEquals("tool-high", exchange.getProperty("resolvedToolRuntime"));
        assertEquals("synthesis-high", exchange.getProperty("resolvedSynthesisRuntime"));
        assertEquals("child tool pass", exchange.getProperty("toolPassResult"));
        assertEquals("child primitive failure", exchange.getProperty("latestPrimitiveFailure"));
        assertEquals("child latest step", exchange.getProperty("latestStepResult"));
        assertEquals("child latest decision", exchange.getProperty("latestDecisionResult"));
        assertEquals("child-create", exchange.getProperty("latestCreateResult", CreateResult.class).createId());
    }

    private static List<Object> events(AgentRunProjection projection) {
        return projection.timeline().stream().map(row -> row.get("type")).toList();
    }

    private static boolean projectionContains(AgentRunProjection projection, String text) {
        return projection.toString().contains(text);
    }
}
