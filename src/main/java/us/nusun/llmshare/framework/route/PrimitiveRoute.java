package us.nusun.llmshare.framework.route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.routine.PrimitiveSupport;

@ApplicationScoped
public class PrimitiveRoute extends RouteBuilder {
    @Inject PrimitiveSupport primitive;

    @Override
    public void configure() {
        from("direct:act")
                .routeId("primitive-act")
                .process(primitive.resolveActRuntimes())
                .process(primitive.markCurrent("act", "actId"))
                .process(primitive.prepareToolPassPrompt())
                .process(primitive.projectionEvent("TOOL_PASS_QUEUED"))
                .to("direct:llm")
                .process(primitive.captureToolPassResult())
                .process(primitive.prepareStepSynthesisPrompt())
                .process(primitive.projectionEvent("SYNTHESIS_PASS_QUEUED"))
                .to("direct:llm")
                .process(primitive.projectionEvent("SYNTHESIS_PASS_COMPLETED"))
                .process(primitive.applyStepResult());

        from("direct:decide")
                .routeId("primitive-decide")
                .process(primitive.resolveSynthesisRuntime("synthesisRuntime"))
                .process(primitive.markCurrent("decide", "decisionId"))
                .process(primitive.prepareDecisionPrompt())
                .process(primitive.projectionEvent("SYNTHESIS_PASS_QUEUED"))
                .to("direct:llm")
                .process(primitive.projectionEvent("SYNTHESIS_PASS_COMPLETED"))
                .process(primitive.applyDecisionResult());

        from("direct:create")
                .routeId("primitive-create")
                .process(primitive.resolveSynthesisRuntime("synthesisRuntime"))
                .process(primitive.markCurrent("create", "createId"))
                .process(primitive.prepareCreatePrompt())
                .process(primitive.projectionEvent("SYNTHESIS_PASS_QUEUED"))
                .to("direct:llm")
                .process(primitive.projectionEvent("SYNTHESIS_PASS_COMPLETED"))
                .process(primitive.applyCreateResult());
    }
}
