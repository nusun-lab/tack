package us.nusun.llmshare.framework.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.routine.PrimitiveSupport;

import static org.apache.camel.builder.Builder.simple;

@ApplicationScoped
public class PrimitiveRoute extends RouteBuilder {
    @Inject PrimitiveSupport primitive;

    @Override
    public void configure() {
        from("direct:act")
                .routeId("primitive-act")
                .process(primitive.resolveActRuntimes())
                .process(primitive.markCurrent("act", "actId"))
                .process(primitive.prepareToolPassPromptContext())
                .setProperty("primitivePrompt", simple("""
                        Mission:
                        ${exchangeProperty.missionGoal}

                        Agent-run input:
                        ${exchangeProperty.agentRunInput}

                        Routine context:
                        ${exchangeProperty.routineSummary}

                        Mission roster:
                        ${exchangeProperty.missionRoster}

                        Act id: ${exchangeProperty.actId}
                        Tool profile: ${exchangeProperty.toolProfileId}
                        Tool-profile guidance:
                        ${exchangeProperty.toolProfileGuidance}

                        Active thoughts:
                        ${exchangeProperty.activeThoughts}

                        Act prompt:
                        ${exchangeProperty.actPrompt}

                        Do the requested work using available tools when useful. Return a compact pass report in this shape:
                        ${bean:primitiveSupport.passReportShape}
                        """))
                .process(primitive.prepareToolPassPrompt())
                .process(primitive.projectionEvent("TOOL_PASS_QUEUED"))
                .to("direct:llm")
                .process(primitive.captureToolPassResult())
                .process(primitive.prepareStepSynthesisPromptContext())
                .setProperty("primitivePrompt", simple("""
                        Mission:
                        ${exchangeProperty.missionGoal}

                        Agent-run input:
                        ${exchangeProperty.agentRunInput}

                        Routine context:
                        ${exchangeProperty.routineSummary}

                        Mission roster:
                        ${exchangeProperty.missionRoster}

                        Act id: ${exchangeProperty.actId}
                        Preceding action objective, for context only:
                        ${exchangeProperty.precedingActionObjective}
                        Do not execute or repeat this objective during synthesis.
                        Latest observation observedAt: ${exchangeProperty.toolPassObservedAt}
                        Latest observation from this act:
                        ${exchangeProperty.toolPassResult}

                        Previous thought snapshots, possibly stale:
                        ${exchangeProperty.activeThoughts}
                        ${bean:primitiveSupport.stepResultRules}
                        """))
                .process(primitive.prepareStepSynthesisPrompt())
                .process(primitive.projectionEvent("SYNTHESIS_PASS_QUEUED"))
                .to("direct:llm")
                .process(primitive.projectionEvent("SYNTHESIS_PASS_COMPLETED"))
                .process(primitive.applyStepResult());

        from("direct:decide")
                .routeId("primitive-decide")
                .process(primitive.resolveSynthesisRuntime("synthesisRuntime"))
                .process(primitive.markCurrent("decide", "decisionId"))
                .process(primitive.prepareDecisionPromptContext())
                .setProperty("primitivePrompt", simple("""
                        Mission:
                        ${exchangeProperty.missionGoal}

                        Agent-run input:
                        ${exchangeProperty.agentRunInput}

                        Routine context:
                        ${exchangeProperty.routineSummary}

                        Decision id: ${exchangeProperty.decisionId}
                        Question: ${exchangeProperty.decisionPrompt}
                        Active thoughts:
                        ${exchangeProperty.activeThoughts}
                        Latest step result:
                        ${exchangeProperty.latestStepResultJson}
                        Return only decisionId, result, confidence, and reason.
                        """))
                .process(primitive.prepareDecisionPrompt())
                .process(primitive.projectionEvent("SYNTHESIS_PASS_QUEUED"))
                .to("direct:llm")
                .process(primitive.projectionEvent("SYNTHESIS_PASS_COMPLETED"))
                .process(primitive.applyDecisionResult());

        from("direct:create")
                .routeId("primitive-create")
                .process(primitive.resolveSynthesisRuntime("synthesisRuntime"))
                .process(primitive.markCurrent("create", "createId"))
                .process(primitive.prepareCreatePromptContext())
                .setProperty("primitivePrompt", simple("""
                        Create id: ${exchangeProperty.createId}
                        Prompt:
                        ${exchangeProperty.createPrompt}
                        Mission:
                        ${exchangeProperty.missionGoal}
                        Agent-run input:
                        ${exchangeProperty.agentRunInput}
                        Routine context:
                        ${exchangeProperty.routineSummary}
                        Mission roster:
                        ${exchangeProperty.missionRoster}
                        Active thoughts:
                        ${exchangeProperty.activeThoughts}
                        Caller JSON schema:
                        ${exchangeProperty.createSchemaJson}
                        """))
                .process(primitive.prepareCreatePrompt())
                .process(primitive.projectionEvent("SYNTHESIS_PASS_QUEUED"))
                .to("direct:llm")
                .process(primitive.projectionEvent("SYNTHESIS_PASS_COMPLETED"))
                .process(primitive.applyCreateResult());
    }
}
