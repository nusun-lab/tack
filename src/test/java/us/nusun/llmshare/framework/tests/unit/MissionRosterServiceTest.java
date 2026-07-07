package us.nusun.llmshare.framework.tests.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void missionRosterContainsOnlyRunsAdmittedToCurrentMission() throws Exception {
        AgentRunStore store = storeWithRuns();
        MissionRosterService service = service(store);

        JsonNode roster = OBJECT_MAPPER.readTree(service.missionRosterPromptJson("mission-1", "run-a"));
        String json = roster.toPrettyString();

        assertEquals(2, roster.size());
        assertTrue(json.contains("persona-a"));
        assertTrue(json.contains("persona-c"));
        assertFalse(json.contains("persona-b"));
        assertFalse(json.contains("run-b-other-mission"));
    }

    @Test
    void missionRosterExcludesRunsFromOtherMissions() throws Exception {
        AgentRunStore store = storeWithRuns();
        MissionRosterService service = service(store);

        JsonNode roster = OBJECT_MAPPER.readTree(service.missionRosterPromptJson("mission-2", "run-b-other-mission"));
        String json = roster.toPrettyString();

        assertEquals(1, roster.size());
        assertTrue(json.contains("persona-b"));
        assertFalse(json.contains("persona-a"));
        assertFalse(json.contains("persona-c"));
    }

    @Test
    void missionRosterMarksSelfByAgentRunIdAndSupportsDuplicatePersonas() throws Exception {
        AgentRunStore store = new AgentRunStore();
        store.putMission(new Mission("mission-1", "Build", List.of(), "active", Map.of(), Instant.now()));
        store.putRun(new AgentRun("run-a-1", "mission-1", "persona-a", "routine-a", Map.of()));
        store.putRun(new AgentRun("run-a-2", "mission-1", "persona-a", "routine-b", Map.of()));
        MissionRosterService service = service(store);

        JsonNode roster = OBJECT_MAPPER.readTree(service.missionRosterPromptJson("mission-1", "run-a-2"));

        assertEquals(false, entry(roster, "run-a-1").get("self").asBoolean());
        assertEquals(true, entry(roster, "run-a-2").get("self").asBoolean());
    }

    @Test
    void missionRosterIncludesCoordinationMetadataAndPromptSafeIdentitiesOnly() throws Exception {
        AgentRunStore store = storeWithRuns();
        MissionRosterService service = service(store);

        JsonNode entry = entry(OBJECT_MAPPER.readTree(service.missionRosterPromptJson("mission-1", "run-a")), "run-a");
        String json = entry.toPrettyString();

        assertEquals("run-a", entry.get("agentRunId").asText());
        assertEquals("persona-a", entry.get("personaId").asText());
        assertEquals("Persona A", entry.get("personaName").asText());
        assertEquals("PA", entry.get("personaLabel").asText());
        assertEquals("routine-a", entry.get("routineId").asText());
        assertEquals("Routine A", entry.get("routineName").asText());
        assertEquals("accepted", entry.get("status").asText());
        assertEquals("run-parent", entry.get("parentAgentRunId").asText());
        assertTrue(json.contains("@persona-a"));
        assertFalse(json.contains("mattermost-token"));
        assertFalse(json.contains("gitea-token"));
        assertFalse(json.contains("plane-token"));
    }

    private static JsonNode entry(JsonNode roster, String agentRunId) {
        for (JsonNode entry : roster) {
            if (agentRunId.equals(entry.get("agentRunId").asText())) {
                return entry;
            }
        }
        throw new AssertionError("Missing roster entry " + agentRunId + " in " + roster.toPrettyString());
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
        return FrameworkFixtures.missionRosterService(store, personas(), routines(), OBJECT_MAPPER);
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
