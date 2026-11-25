package us.nusun.tack.framework.logging;

import static us.nusun.tack.framework.FrameworkText.firstNonBlank;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.tack.framework.persona.Persona;

@ApplicationScoped
public class LogTokens {
    private static final int MAX_TOOL_VALUE_LENGTH = 4000;
    private final boolean ansiEnabled;

    public LogTokens(@ConfigProperty(name = "llm.log.ansi-enabled", defaultValue = "true") boolean ansiEnabled) {
        this.ansiEnabled = ansiEnabled;
    }

    public void print(String message) {
        System.out.println(message);
    }

    public String wrap(String label, String color, String message) {
        return wrapWithColor(label, color, message, false);
    }

    public String wrapDim(String label, String color, String message) {
        return wrapWithColor(label, color, message, true);
    }

    public void printTool(Persona persona, String message) {
        print(wrapDim(firstNonBlank(persona.label(), persona.name()), persona.color(), message));
    }

    public static String abbreviateToolValue(Object value) {
        String text = String.valueOf(value);
        return text.length() <= MAX_TOOL_VALUE_LENGTH ? text : text.substring(0, MAX_TOOL_VALUE_LENGTH) + "...(truncated)";
    }

    private String wrapWithColor(String label, String color, String message, boolean dim) {
        String text = "[" + label + "] " + message;
        if (!ansiEnabled || color == null || color.isBlank()) {
            return text;
        }
        String prefix = "\u001B[" + (dim ? dimColor(color) : color) + "m";
        return prefix + text + "\u001B[0m";
    }

    private static String dimColor(String color) {
        String[] parts = color.split(";");
        if (parts.length != 5 || !"38".equals(parts[0]) || !"2".equals(parts[1])) {
            return color;
        }
        try {
            return "38;2;"
                    + darken(Integer.parseInt(parts[2])) + ";"
                    + darken(Integer.parseInt(parts[3])) + ";"
                    + darken(Integer.parseInt(parts[4]));
        } catch (NumberFormatException ignored) {
            return color;
        }
    }

    private static int darken(int value) {
        return Math.max(0, (int) Math.round(value * 0.55));
    }
}
