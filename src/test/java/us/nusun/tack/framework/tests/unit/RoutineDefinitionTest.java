package us.nusun.tack.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.model.RoutineDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineDefinitionTest {
    @Test
    void routineCanDeclareZeroOrMultipleStableSubroutines() {
        RoutineDefinition zero = RoutineDefinition.builder("test/zero")
                .name("Zero")
                .description("No subroutines.")
                .routeUri("direct:test-zero")
                .build();
        RoutineDefinition routine = RoutineDefinition.builder("test/routine")
                .name("Test Routine")
                .main("Full routine target.", "direct:test-routine")
                .subroutine("phase-a", "Perform only phase A.", "direct:test-routine-phase-a")
                .subroutine("phase-b", "Perform only phase B.", "direct:test-routine-phase-b")
                .build();

        assertEquals(List.of(), zero.subroutines());
        assertEquals("Full routine target.", routine.description());
        assertEquals(List.of("phase-a", "phase-b"), routine.subroutines().stream().map(item -> item.id()).toList());
        assertEquals("direct:test-routine", routine.routeUri(null));
        assertEquals("direct:test-routine-phase-a", routine.routeUri("phase-a"));
        assertEquals("direct:test-routine-phase-b", routine.requireSubroutine("phase-b").routeUri());
    }

    @Test
    void duplicateOrMissingSubroutineValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RoutineDefinition.builder("test/routine")
                .name("Test")
                .description("Test")
                .routeUri("direct:test")
                .subroutine("phase-a", "A", "direct:a")
                .subroutine("phase-a", "Again", "direct:b")
                .build());
        assertThrows(IllegalArgumentException.class, () -> RoutineDefinition.builder("test/routine")
                .name("Test")
                .description("Test")
                .routeUri("direct:test")
                .subroutine(" ", "A", "direct:a"));
        assertThrows(IllegalArgumentException.class, () -> RoutineDefinition.builder("test/routine")
                .name("Test")
                .description("Test")
                .routeUri("direct:test")
                .subroutine("phase-a", " ", "direct:a"));
        assertThrows(IllegalArgumentException.class, () -> RoutineDefinition.builder("test/routine")
                .name("Test")
                .description("Test")
                .routeUri("direct:test")
                .subroutine("phase-a", "A", " "));
    }

    @Test
    void missingRoutineValuesAndUnknownSubroutineAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RoutineDefinition.builder(" ")
                .name("Test")
                .description("Test")
                .routeUri("direct:test")
                .build());
        assertThrows(IllegalArgumentException.class, () -> RoutineDefinition.builder("test/routine")
                .name(" ")
                .description("Test")
                .routeUri("direct:test")
                .build());
        assertThrows(IllegalArgumentException.class, () -> RoutineDefinition.builder("test/routine")
                .name("Test")
                .description(" ")
                .routeUri("direct:test")
                .build());
        assertThrows(IllegalArgumentException.class, () -> RoutineDefinition.builder("test/routine")
                .name("Test")
                .description("Test")
                .routeUri(" ")
                .build());

        RoutineDefinition routine = RoutineDefinition.builder("test/routine")
                .name("Test")
                .description("Test")
                .routeUri("direct:test")
                .build();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> routine.requireSubroutine("phase-a"));
        assertTrue(failure.getMessage().contains("Unknown subroutine phase-a for routine test/routine"));
    }

    @Test
    void passSpecificLaneBindingsOverrideRoutineDefault() {
        RoutineDefinition routine = RoutineDefinition.builder("test/routine")
                .name("Test")
                .description("Test")
                .routeUri("direct:test")
                .defaultLane("high-pass")
                .withLaneBinding(LlmPassType.SYNTHESIS, "low-pass")
                .withLaneBinding(LlmPassType.CONSOLIDATION, "low-pass")
                .build();

        assertEquals("high-pass", routine.laneFor(LlmPassType.ACTION));
        assertEquals("high-pass", routine.laneFor(LlmPassType.CREATION));
        assertEquals("low-pass", routine.laneFor(LlmPassType.SYNTHESIS));
        assertEquals("high-pass", routine.laneFor(LlmPassType.DECISION));
        assertEquals("low-pass", routine.laneFor(LlmPassType.CONSOLIDATION));
    }

    @Test
    void startableInventoryAdvertisesOnlyTopLevelRoutineSelection() {
        RoutineCatalog catalog = new RoutineCatalog(List.of(RoutineDefinition.builder("test/routine")
                .name("Test Routine")
                .description("A routine with focused entry points.")
                .routeUri("direct:test-routine")
                .subroutine("phase-a", "Perform only phase A.", "direct:test-routine-phase-a")
                .build()));

        String json = catalog.startableJson();

        assertTrue(json.contains("test/routine"));
        assertTrue(json.contains("Test Routine"));
        assertTrue(json.contains("A routine with focused entry points."));
        assertFalse(json.contains("subroutines"));
        assertFalse(json.contains("phase-a"));
        assertFalse(json.contains("Perform only phase A."));
        assertFalse(json.contains("direct:test-routine-phase-a"));
        assertFalse(json.contains("main routine"));
    }
}
