package us.nusun.llmshare.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.llmshare.framework.inventory.PersonaCatalog;
import us.nusun.llmshare.framework.inventory.RoutineCatalog;
import us.nusun.llmshare.framework.model.Mission;
import us.nusun.llmshare.framework.model.Persona;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.service.MissionRosterService;
import us.nusun.llmshare.framework.state.AgentRun;
import us.nusun.llmshare.framework.state.AgentRunStore;
import us.nusun.llmshare.framework.tests.support.FrameworkFixtures;

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

        String roster = service.missionRosterPrompt("mission-1");

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

        String roster = service.missionRosterPrompt("mission-2");

        assertEquals(1, entryCount(roster));
        assertTrue(roster.contains("- name: Persona B"));
        assertTrue(roster.contains("routine: Routine A"));
        assertFalse(roster.contains("Persona A"));
        assertFalse(roster.contains("Persona C"));
    }

    @Test
    void missionRosterSupportsDuplicatePersonasWithoutRunMetadata() {
        AgentRunStore store = new AgentRunStore();
        store.putMission(new Mission("mission-1", "Build", List.of(), "active", Map.of(), Instant.now()));
        store.putRun(new AgentRun("run-a-1", "mission-1", "persona-a", "routine-a", Map.of()));
        store.putRun(new AgentRun("run-a-2", "mission-1", "persona-a", "routine-b", Map.of()));
        MissionRosterService service = service(store);

        String roster = service.missionRosterPrompt("mission-1");

        assertEquals(2, entryCount(roster));
        assertTrue(roster.contains("- name: Persona A"));
        assertTrue(roster.contains("routine: Routine A"));
        assertTrue(roster.contains("routine: Routine B"));
        assertFalse(roster.contains("run-a-1"));
        assertFalse(roster.contains("run-a-2"));
        assertFalse(roster.contains("self"));
    }

    @Test
    void missionRosterIncludesOnlyNamesRoutineNamesAndPromptSafePlatformIdentities() {
        AgentRunStore store = storeWithRuns();
        MissionRosterService service = service(store);

        String roster = service.missionRosterPrompt("mission-1");

        assertTrue(roster.contains("- name: Persona A"));
        assertTrue(roster.contains("routine: Routine A"));
        assertTrue(roster.contains("platform identities: gitea @persona-a, mattermost @persona-a, plane @persona-a"));
        assertTrue(roster.contains("- name: Persona C"));
        assertTrue(roster.contains("routine: Routine B"));
        assertTrue(roster.contains("platform identities: mattermost @persona-c"));
        assertFalse(roster.contains("run-a"));
        assertFalse(roster.contains("personaId"));
        assertFalse(roster.contains("routineId"));
        assertFalse(roster.contains("PA"));
        assertFalse(roster.contains("accepted"));
        assertFalse(roster.contains("run-parent"));
        assertFalse(roster.contains("Routine A description"));
        assertFalse(roster.contains("mattermost-token"));
        assertFalse(roster.contains("gitea-token"));
        assertFalse(roster.contains("plane-token"));
        assertFalse(roster.toLowerCase().contains("credential"));
    }

    private static long entryCount(String roster) {
        return roster.lines().filter(line -> line.startsWith("- ")).count();
    }

    private static AgentRunStore storeWithRuns() {
        AgentRunStore store = new AgentRunStore();
        store.putMission(new Mission("mission-1", "Build", List.of(), "active", Map.of(), Instant.now()));
        store.putMission(new Mission("mission-2", "Other", List.of(), "active", Map.of(), Instant.now()));
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
                        .identity("mattermost", "mattermost-token", "@persona-a")
                        .identity("gitea", "gitea-token", "@persona-a")
                        .identity("plane", "plane-token", "@persona-a")
                        .build(),
                Persona.builder("persona-b")
                        .name("Persona B")
                        .label("PB")
                        .description("Persona B description")
                        .identity("mattermost", "mattermost-token-b", "@persona-b")
                        .build(),
                Persona.builder("persona-c")
                        .name("Persona C")
                        .label("PC")
                        .description("Persona C description")
                        .identity("mattermost", "mattermost-token-c", "@persona-c")
                        .build()));
    }

    private static RoutineCatalog routines() {
        return new RoutineCatalog(List.of(
                RoutineDefinition.builder("routine-a").name("Routine A").description("Routine A description").routeUri("direct:a").build(),
                RoutineDefinition.builder("routine-b").name("Routine B").description("Routine B description").routeUri("direct:b").build()));
    }
}
