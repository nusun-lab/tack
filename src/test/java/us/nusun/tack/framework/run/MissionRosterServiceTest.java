package us.nusun.tack.framework.run;

import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.persona.PersonaCatalog;
import us.nusun.tack.framework.routine.RoutineCatalog;
import us.nusun.tack.framework.persona.Persona;
import us.nusun.tack.framework.routine.RoutineDefinition;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionRosterServiceTest {
    @Test
    void missionRosterContainsOnlyRunsAdmittedToCurrentMission() {
        AgentRunStore store = storeWithRuns();
        MissionRosterService service = service(store);

        String roster = service.rosterView("mission-1", "history", "full", null, null, null, null);

        assertEquals(2, entryCount(roster));
        assertTrue(roster.contains("- name: Persona A"));
        assertTrue(roster.contains("routine: Routine A"));
        assertTrue(roster.contains("- name: Persona C"));
        assertTrue(roster.contains("routine: Routine B"));
        assertFalse(roster.contains("Persona B"));
        assertFalse(roster.contains("run-b-other-mission"));
    }

    @Test
    void missionRosterExcludesRunsFromOtherMissions() {
        AgentRunStore store = storeWithRuns();
        MissionRosterService service = service(store);

        String roster = service.rosterView("mission-2", "history", "full", null, null, null, null);

        assertEquals(1, entryCount(roster));
        assertTrue(roster.contains("- name: Persona B"));
        assertTrue(roster.contains("routine: Routine A"));
        assertFalse(roster.contains("Persona A"));
        assertFalse(roster.contains("Persona C"));
    }

    @Test
    void missionRosterSupportsDuplicatePersonasWithRunMetadata() {
        AgentRunStore store = new AgentRunStore();
        store.putMission(new Mission("mission-1", "Build", List.of(), "active", Instant.now()));
        store.putRun(new AgentRun("run-a-1", "mission-1", "persona-a", "routine-a", Map.of()));
        store.putRun(new AgentRun("run-a-2", "mission-1", "persona-a", "routine-b", Map.of()));
        MissionRosterService service = service(store);

        String roster = service.rosterView("mission-1", "history", "full", null, null, null, null);

        assertEquals(2, entryCount(roster));
        assertTrue(roster.contains("- name: Persona A"));
        assertTrue(roster.contains("routine: Routine A"));
        assertTrue(roster.contains("routine: Routine B"));
        assertTrue(roster.contains("agentRunId: run-a-1"));
        assertTrue(roster.contains("agentRunId: run-a-2"));
        assertTrue(roster.contains("personaId: persona-a"));
        assertTrue(roster.contains("routineId: routine-a"));
        assertTrue(roster.contains("routineId: routine-b"));
        assertTrue(roster.contains("status: running"));
        assertFalse(roster.contains("self"));
    }

    @Test
    void missionRosterIncludesNamesIdsStatusAndPromptSafePlatformIdentities() {
        AgentRunStore store = storeWithRuns();
        MissionRosterService service = service(store);

        String roster = service.rosterView("mission-1", "history", "full", null, null, null, null);

        assertTrue(roster.contains("- name: Persona A"));
        assertTrue(roster.contains("routine: Routine A"));
        assertTrue(roster.contains("platform identities: alpha-mcp=@persona-a, beta-mcp=@persona-a, gamma-mcp=@persona-a"));
        assertTrue(roster.contains("- name: Persona C"));
        assertTrue(roster.contains("routine: Routine B"));
        assertTrue(roster.contains("platform identities: alpha-mcp=@persona-c"));
        assertTrue(roster.contains("personaId: persona-a"));
        assertTrue(roster.contains("routineId: routine-a"));
        assertTrue(roster.contains("agentRunId: run-a"));
        assertTrue(roster.contains("status: accepted"));
        assertTrue(roster.contains("personaId: persona-c"));
        assertTrue(roster.contains("routineId: routine-b"));
        assertTrue(roster.contains("agentRunId: run-c"));
        assertTrue(roster.contains("status: running"));
        assertFalse(roster.contains("PA"));
        assertTrue(roster.contains("parentAgentRunId: run-parent"));
        assertFalse(roster.contains("Routine A description"));
        assertFalse(roster.contains("alpha-secret"));
        assertFalse(roster.contains("beta-secret"));
        assertFalse(roster.contains("gamma-secret"));
        assertFalse(roster.toLowerCase().contains("credential"));
    }


    @Test
    void missionRosterIncludesSubroutineAndFocusedInput() {
        AgentRunStore store = new AgentRunStore();
        store.putMission(new Mission("mission-1", "Build", List.of(), "active", Instant.now()));
        AgentRun run = new AgentRun(
                "run-phase-a",
                "mission-1",
                null,
                "persona-a",
                "routine-a",
                "phase-a",
                Map.of("input", Map.of("focus", "Validate phase A behavior.")));
        run.status(AgentRun.STATUS_SCHEDULED);
        store.putRun(run);
        MissionRosterService service = service(store);

        String roster = service.rosterView("mission-1", "active", "full", null, null, null, null);

        assertTrue(roster.contains("subroutineId: phase-a"));
        assertTrue(roster.contains("subroutine: Perform phase A."));
        assertTrue(roster.contains("target: subroutine"));
        assertTrue(roster.contains("input: {\"focus\":\"Validate phase A behavior.\"}"));
    }

    @Test
    void activeMissionRosterExcludesTerminalRuns() {
        AgentRunStore store = storeWithRuns();
        AgentRun complete = new AgentRun("run-complete", "mission-1", "persona-a", "routine-a", Map.of());
        complete.status(AgentRun.STATUS_COMPLETE);
        store.putRun(complete);
        MissionRosterService service = service(store);

        String roster = service.rosterView("mission-1", "active", "full", null, null, null, null);

        assertFalse(roster.contains("run-complete"));
        assertEquals(2, entryCount(roster));
    }

    @Test
    void missionRosterToolDefaultsToActiveContacts() {
        AgentRunStore store = storeWithRuns();
        AgentRun complete = new AgentRun("run-complete", "mission-1", "persona-a", "routine-a", Map.of());
        complete.status(AgentRun.STATUS_COMPLETE);
        store.putRun(complete);

        String roster = new MissionRosterTool(service(store), "mission-1", 5)
                .mission_roster(null, null, null, null, null);

        assertEquals(2, entryCount(roster));
        assertTrue(roster.contains("Persona A"));
        assertTrue(roster.contains("statuses=accepted"));
        assertTrue(roster.contains("handles: alpha-mcp=@persona-a"));
        assertFalse(roster.contains("agentRunId="));
        assertFalse(roster.contains("run-complete"));
        assertFalse(roster.contains("run-b-other-mission"));
    }

    @Test
    void missionRosterToolHistoryDefaultsToRunHistory() {
        AgentRunStore store = storeWithRuns();
        AgentRun complete = new AgentRun("run-complete", "mission-1", "persona-a", "routine-a", Map.of());
        complete.status(AgentRun.STATUS_COMPLETE);
        store.putRun(complete);

        String roster = new MissionRosterTool(service(store), "mission-1", 5)
                .mission_roster("history", null, null, null, null);

        assertEquals(3, entryCount(roster));
        assertTrue(roster.contains("agentRunId=run-complete"));
        assertTrue(roster.contains("startedAt="));
        assertFalse(roster.contains("run-b-other-mission"));
    }

    @Test
    void missionRosterToolCanReturnOnlyRequestedPlatformHandles() {
        String roster = new MissionRosterTool(service(storeWithRuns()), "mission-1", 5)
                .mission_roster(null, "contacts", "persona-a", null, "beta-mcp");

        assertEquals(1, entryCount(roster));
        assertTrue(roster.contains("beta-mcp=@persona-a"));
        assertFalse(roster.contains("alpha-mcp=@persona-a"));
        assertFalse(roster.contains("gamma-mcp=@persona-a"));
        assertFalse(roster.contains("Persona C"));
    }

    @Test
    void missionRosterToolCoverageIncludesCapacityButOmitsHandlesAndRunIds() {
        String roster = new MissionRosterTool(service(storeWithRuns()), "mission-1", 5)
                .mission_roster(null, "coverage", null, null, null);

        assertEquals(2, entryCount(roster));
        assertTrue(roster.contains("capacity: active=2 maximum=5 remaining=3"));
        assertTrue(roster.contains("routineId=routine-a"));
        assertFalse(roster.contains("handles:"));
        assertFalse(roster.contains("agentRunId="));
    }

    @Test
    void missionRosterContactsCollapseMultipleRunsForOnePersona() {
        AgentRunStore store = storeWithRuns();
        AgentRun second = new AgentRun("run-a-2", "mission-1", "persona-a", "routine-b", Map.of());
        second.status(AgentRun.STATUS_RUNNING);
        store.putRun(second);

        String roster = new MissionRosterTool(service(store), "mission-1", 5)
                .mission_roster(null, "contacts", "persona-a", null, null);

        assertEquals(1, entryCount(roster));
        assertTrue(roster.contains("routineIds=routine-a,routine-b"));
        assertTrue(roster.contains("statuses=accepted,running"));
    }

    private static long entryCount(String roster) {
        return roster.lines().filter(line -> line.startsWith("- ")).count();
    }

    private static AgentRunStore storeWithRuns() {
        AgentRunStore store = new AgentRunStore();
        store.putMission(new Mission("mission-1", "Build", List.of(), "active", Instant.now()));
        store.putMission(new Mission("mission-2", "Other", List.of(), "active", Instant.now()));
        AgentRun runA = new AgentRun("run-a", "mission-1", "run-parent", "persona-a", "routine-a", Map.of());
        runA.status(AgentRun.STATUS_ACCEPTED);
        AgentRun runC = new AgentRun("run-c", "mission-1", "persona-c", "routine-b", Map.of());
        runC.status(AgentRun.STATUS_RUNNING);
        AgentRun runB = new AgentRun("run-b-other-mission", "mission-2", "persona-b", "routine-a", Map.of());
        runB.status(AgentRun.STATUS_ACCEPTED);
        store.putRun(runA);
        store.putRun(runC);
        store.putRun(runB);
        return store;
    }

    private static MissionRosterService service(AgentRunStore store) {
        return FrameworkFixtures.missionRosterService(store, personas(), routines());
    }

    private static PersonaCatalog personas() {
        return new PersonaCatalog(List.of(
                Persona.builder("persona-a")
                        .name("Persona A")
                        .label("PA")
                        .description("Persona A description")
                        .identity(FrameworkFixtures.SERVER_ALPHA, "alpha-secret", "@persona-a")
                        .identity(FrameworkFixtures.SERVER_BETA, "beta-secret", "@persona-a")
                        .identity(FrameworkFixtures.SERVER_GAMMA, "gamma-secret", "@persona-a")
                        .build(),
                Persona.builder("persona-b")
                        .name("Persona B")
                        .label("PB")
                        .description("Persona B description")
                        .identity(FrameworkFixtures.SERVER_ALPHA, "alpha-secret-b", "@persona-b")
                        .build(),
                Persona.builder("persona-c")
                        .name("Persona C")
                        .label("PC")
                        .description("Persona C description")
                        .identity(FrameworkFixtures.SERVER_ALPHA, "alpha-secret-c", "@persona-c")
                        .build()));
    }

    private static RoutineCatalog routines() {
        return new RoutineCatalog(List.of(
                RoutineDefinition.builder("routine-a").name("Routine A").description("Routine A description").routeUri("direct:a")
                        .subroutine("phase-a", "Perform phase A.", "direct:a-phase-a").build(),
                RoutineDefinition.builder("routine-b").name("Routine B").description("Routine B description").routeUri("direct:b").build()));
    }
}
