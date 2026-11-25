package us.nusun.tack.framework.routine;

public record SubroutineDefinition(
        String id,
        String description,
        String routeUri
) {
    public SubroutineDefinition {
        id = requireText(id, "Subroutine missing id");
        description = requireText(description, "Subroutine " + id + " missing description");
        routeUri = requireText(routeUri, "Subroutine " + id + " missing routeUri");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
