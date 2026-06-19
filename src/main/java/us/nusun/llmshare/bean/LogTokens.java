package us.nusun.llmshare.bean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.team.AgentPersonaRegistry;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class LogTokens {
    private final Map<String, String> colorByAgentLabel;

    @ConfigProperty(name = "llm.log.ansi-enabled", defaultValue = "true")
    boolean ansiEnabled;

    @ConfigProperty(name = "llm.log.enabled", defaultValue = "true")
    boolean loggingEnabled;

    @Inject
    public LogTokens(AgentPersonaRegistry agentPersonaRegistry) {
        this.colorByAgentLabel = agentPersonaRegistry.allPersonas().stream()
                .collect(Collectors.toMap(
                        persona -> persona.agentLabel() == null ? "" : persona.agentLabel(),
                        persona -> persona.ansiColor() == null ? "" : persona.ansiColor(),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
    }

    public String color(String ansiColor) {
        return ansiEnabled ? sgr(ansiColor) : "";
    }

    public String reset() {
        return ansiEnabled ? "\u001B[0m" : "";
    }

    public void print(String message) {
        if (loggingEnabled) {
            System.out.println(message);
        }
    }

    public String wrap(String agentLabel, String message) {
        if (!ansiEnabled) {
            return message;
        }
        return sgr(colorFor(agentLabel)) + message + reset();
    }

    public String wrapDim(String agentLabel, String message) {
        if (!ansiEnabled) {
            return message;
        }
        return sgr(dimColorFor(agentLabel)) + message + reset();
    }

    private String sgr(String params) {
        return "\u001B[" + params + "m";
    }

    private String colorFor(String agentLabel) {
        return colorByAgentLabel.getOrDefault(agentLabel == null ? "" : agentLabel, "");
    }

    private String dimColorFor(String agentLabel) {
        String color = colorFor(agentLabel);
        if (color.isBlank()) {
            return color;
        }

        String[] parts = color.split(";");
        if (parts.length != 5) {
            return color;
        }

        int red = Integer.parseInt(parts[2]);
        int green = Integer.parseInt(parts[3]);
        int blue = Integer.parseInt(parts[4]);

        return "38;2;"
                + darken(red) + ";"
                + darken(green) + ";"
                + darken(blue);
    }

    private int darken(int value) {
        return Math.max(0, (int) Math.round(value * 0.55));
    }
}
