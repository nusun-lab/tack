package us.nusun.tack.framework.model;

/** A completed Tack act, decision, or creation. */
public sealed interface PrimitiveResult permits StepResult, DecisionResult, CreateResult {
}
