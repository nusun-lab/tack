package us.nusun.tack.framework.routine;

/** A completed Tack act, decision, or creation. */
public sealed interface PrimitiveResult permits StepResult, DecisionResult, CreateResult {
}
