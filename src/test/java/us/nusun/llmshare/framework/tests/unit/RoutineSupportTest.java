package us.nusun.llmshare.framework.tests.unit;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.routine.RoutineSupport;
import us.nusun.llmshare.framework.state.ThoughtStack;
import us.nusun.llmshare.framework.routine.ThoughtStackSupport;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;
import us.nusun.llmshare.framework.tests.support.TestCamelContexts;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.apache.camel.builder.Builder.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineSupportTest {
    @Test
    void actHelperWritesConventionalCamelProperties() throws Exception {
        RoutineSupport support = new RoutineSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(
                "persona-a",
                "routine-a");

        support.act("collaboration-sync")
                .prompt("Ask one focused question.")
                .toolProfile(FrameworkFixtures.PROFILE_ALPHA)
                .lane("high-pass")
                .process(exchange);

        assertEquals("collaboration-sync", exchange.getProperty("actId"));
        assertEquals("Ask one focused question.", exchange.getProperty("actPrompt"));
        assertEquals(FrameworkFixtures.PROFILE_ALPHA, exchange.getProperty("toolProfile"));
        assertEquals("high-pass", exchange.getProperty("lane"));
    }

    @Test
    void actHelperClearsOptionalPrimitivePropertiesWhenUnset() throws Exception {
        RoutineSupport support = new RoutineSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun("persona-a", "routine-a");

        support.act("first")
                .prompt("First.")
                .lane("high-pass")
                .process(exchange);
        support.act("second")
                .prompt("Second.")
                .process(exchange);

        assertEquals(null, exchange.getProperty("lane"));
    }

    @Test
    void decideHelperWritesConventionalCamelProperties() throws Exception {
        RoutineSupport support = new RoutineSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(
                "persona-a",
                "routine-a");

        support.decide("ready")
                .prompt("Is the setup ready?")
                .lane("high-pass")
                .process(exchange);

        assertEquals("ready", exchange.getProperty("decisionId"));
        assertEquals("Is the setup ready?", exchange.getProperty("decisionPrompt"));
        assertEquals("high-pass", exchange.getProperty("lane"));
    }

    @Test
    void createHelperWritesConventionalCamelProperties() throws Exception {
        RoutineSupport support = new RoutineSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(
                "persona-b",
                "routine-b");

        support.create("planned-agent-runs")
                .schemaId("agent-run-list")
                .schema("classpath:schemas/example/software-team/agent-run-requests-schema.json")
                .prompt("Create the initial runs.")
                .process(exchange);

        assertEquals("planned-agent-runs", exchange.getProperty("createId"));
        assertEquals("agent-run-list", exchange.getProperty("schemaId"));
        assertEquals(
                "classpath:schemas/example/software-team/agent-run-requests-schema.json",
                exchange.getProperty("createSchema"));
        assertEquals("Create the initial runs.", exchange.getProperty("createPrompt"));
    }

    @Test
    void createHelperEvaluatesExpressionPromptAgainstExchangeAndCatalogBeans() throws Exception {
        RoutineSupport support = new RoutineSupport();
        try (DefaultCamelContext context = TestCamelContexts.quiet()) {
            FrameworkFixtures.bindCatalogBeans(context);
            context.start();
            Exchange exchange = new DefaultExchange(context);
            exchange.setProperty("mission", new Mission(
                    "mission-1",
                    "Build a parking lot manager app.",
                    List.of(),
                    "active",
                    Map.of(),
                    Instant.now()));

            support.create("planned-agent-runs")
                    .schemaJson("{\"type\":\"object\"}")
                    .prompt(simple("""
                            Mission:
                            ${exchangeProperty.mission.goal}

                            Personas:
                            ${bean:personaCatalog.agentRunStartAssignablePromptJson}

                            Routines:
                            ${bean:routineCatalog.startableJson}

                            Tool profiles:
                            ${bean:toolProfileCatalog.promptJson}
                            """))
                    .process(exchange);

            String prompt = exchange.getProperty("createPrompt", String.class);
            assertTrue(prompt.contains("Build a parking lot manager app."));
            assertTrue(prompt.contains("persona-a"));
            assertTrue(prompt.contains("routine-a"));
            assertTrue(prompt.contains(FrameworkFixtures.PROFILE_GAMMA));
        }
    }

    @Test
    void staticStringPromptStillEvaluatesAsStaticText() throws Exception {
        RoutineSupport support = new RoutineSupport();
        Exchange exchange = FrameworkFixtures.exchangeWithRun(
                "persona-b",
                "routine-b");

        support.create("static-create")
                .schemaJson("{\"type\":\"object\"}")
                .prompt("Static prompt text.")
                .process(exchange);

        assertEquals("Static prompt text.", exchange.getProperty("createPrompt"));
    }

    @Test
    void thoughtHelpersDelegateToThoughtStackSupport() throws Exception {
        RoutineSupport support = new RoutineSupport();
        FrameworkFixtures.set(support, "thoughts", new ThoughtStackSupport());
        Exchange exchange = FrameworkFixtures.exchangeWithRun(
                "persona-a",
                "routine-a");

        support.push("Scoped routine context.", 300).process(exchange);
        ThoughtStack stack = exchange.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);

        assertEquals(1, stack.frames().size());
        assertEquals("Scoped routine context.", stack.frames().getFirst().context());

        support.pop().process(exchange);

        assertTrue(stack.frames().isEmpty());
    }

    @Test
    void namedThoughtScopesValidateNestedExitOrder() throws Exception {
        RoutineSupport support = new RoutineSupport();
        FrameworkFixtures.set(support, "thoughts", new ThoughtStackSupport());
        Exchange exchange = FrameworkFixtures.exchangeWithRun(
                "persona-a",
                "routine-a");

        support.enterThought("routine", "Routine context.", 300).process(exchange);
        ThoughtStack namedStack = exchange.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);
        assertEquals("routine", namedStack.frames().getFirst().name());
        support.enterThought("loop", "Loop context.", 100).process(exchange);

        Exception failure = assertThrows(
                IllegalStateException.class,
                () -> support.exitThought("routine").process(exchange));

        assertTrue(failure.getMessage().contains("active thought scope is loop"));

        support.exitThought("loop").process(exchange);
        support.exitThought("routine").process(exchange);
        ThoughtStack stack = exchange.getProperty(ThoughtStackSupport.STACK_PROPERTY, ThoughtStack.class);

        assertTrue(stack.frames().isEmpty());
    }
}
