package us.nusun.llmshare.framework.logging;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class LogTokens {
    private final boolean ansiEnabled;

    public LogTokens(@ConfigProperty(name = "llm.log.ansi-enabled", defaultValue = "true") boolean ansiEnabled) {
        this.ansiEnabled = ansiEnabled;
    }

    public void print(String message) {
        System.out.println(message);
    }

    public String wrapDim(String label, String color, String message) {
        String text = "[" + label + "] " + message;
        if (!ansiEnabled || color == null || color.isBlank()) {
            return text;
        }
        String prefix = "\u001B[" + dimColor(color) + "m";
        return prefix + text + "\u001B[0m";
    }

    private static String dimColor(String color) {
        String[] parts = color.split(";");
        if (parts.length != 5 || !"38".equals(parts[0]) || !"2".equals(parts[1])) {
            return color;
        }
        return "38;2;"
                + darken(Integer.parseInt(parts[2])) + ";"
                + darken(Integer.parseInt(parts[3])) + ";"
                + darken(Integer.parseInt(parts[4]));
    }

    private static int darken(int value) {
        return Math.max(0, (int) Math.round(value * 0.55));
    }
}
