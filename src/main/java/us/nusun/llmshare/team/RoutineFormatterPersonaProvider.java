package us.nusun.llmshare.team;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
public class RoutineFormatterPersonaProvider implements AgentPersonaProvider {
    private static final AgentPersonaDefinition PERSONA = new AgentPersonaDefinition(
            "software-team/routine-formatter",
            "routine-formatter",
            "Routine Formatter",
            "direct:invoke-routine-formatter-llm",
            "invoke-routine-formatter-llm",
            "FMT",
            "38;2;160;160;160",
            "Routine Formatter ready.",
            "Routine Formatter idle.",
            "Convert raw routine output into the strict structured step schema.",
            """
            You are a strict structured formatter.
            You do not have tools.
            You do not invent facts.
            You summarize only from the provided routine context, raw agent output, and tool evidence.
            If the evidence is missing or ambiguous, say so explicitly in the structured fields instead of guessing.
            Return valid JSON only when asked for structured output.
            """,
            Map.of(),
            false,
            false
    );

    @Override
    public AgentPersonaDefinition persona() {
        return PERSONA;
    }
}
