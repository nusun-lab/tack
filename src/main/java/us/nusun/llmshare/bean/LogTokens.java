package us.nusun.llmshare.bean;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class LogTokens {
    private static final String DIRECTOR_COLOR = "38;2;64;224;255";
    private static final String PM_COLOR = "38;2;255;179;71";
    private static final String PO_COLOR = "38;2;114;209;163";
    private static final String DEV_COLOR = "38;2;106;169;255";

    @ConfigProperty(name = "llm.log.ansi-enabled", defaultValue = "true")
    boolean ansiEnabled;

    public String color(String ansiColor) {
        return ansiEnabled ? sgr(ansiColor) : "";
    }

    public String reset() {
        return ansiEnabled ? "\u001B[0m" : "";
    }

    public void print(String message) {
        System.out.println(message);
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
        return switch (agentLabel == null ? "" : agentLabel) {
            case "DIR" -> DIRECTOR_COLOR;
            case "PM" -> PM_COLOR;
            case "PO" -> PO_COLOR;
            case "DEV" -> DEV_COLOR;
            default -> "";
        };
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
