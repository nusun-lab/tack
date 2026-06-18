package us.nusun.llmshare.bean;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import us.nusun.llmshare.model.LlmJobEnvelope;
import us.nusun.llmshare.model.LlmJobResult;
import us.nusun.llmshare.team.TeamRoleDefinition;
import us.nusun.llmshare.team.TeamRoleRegistry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
@Named("mockLlmResponder")
public class MockLlmResponder {
    @Inject
    TeamRoleRegistry teamRoleRegistry;

    @Inject
    ObjectMapper objectMapper;

    public LlmJobResult respond(LlmJobEnvelope job) {
        Instant startedAt = Instant.now();
        String content;
        if (job.responseSchema() != null && !job.responseSchema().isBlank()) {
            content = switch (job.responseSchema()) {
                case "classpath:schemas/director-objectives-schema.json" -> directorAssignmentsJson();
                case "classpath:schemas/agent-routine-step-schema.json" -> """
                        {"role":"%s","step":"mock-step","routine":"Mock routine","summary":"Completed the current routine step for the requested product using the available evidence.","nextAction":"Continue the next routine step and keep the team aligned on the requested product.","actionsTaken":["Reviewed the available mock context."],"decisions":["Keep the flow moving."],"openQuestions":[],"artifactsTouched":["mock-artifact"],"needsFollowup":true,"doneCandidate":false}
                        """.formatted(job.requester());
                case "classpath:schemas/agent-done-check-schema.json" -> {
                    int iteration = extractIteration(job.prompt());
                    boolean done = iteration >= 2;
                    yield """
                            {"done":%s,"reason":"%s"}
                            """.formatted(done, done
                            ? "Two structured routine cycles are enough to satisfy the current objective in the mock flow."
                            : "Continue the daily routine before declaring the objective complete.");
                }
                case "classpath:schemas/project-collaboration-setup-schema.json" -> """
                        {"collaborationReady":false,"chatChannelReady":false,"planeProjectReady":false,"giteaOrgReady":false,"forumAnnouncementReady":false,"mattermostChannel":"#forum-agents","planeProject":"","giteaOrg":"","reason":"Mock flow has no real collaboration setup evidence yet."}
                        """;
                default -> """
                        {"role":"%s","summary":"Synthetic schema response.","nextAction":"None."}
                        """.formatted(job.requester());
            };
        } else {
            content = switch (job.requester()) {
                case "software-team/director" -> "Clarify the mission for the assigned role in the requested product effort.";
                case "software-team/project-manager" -> "Coordinated the current work for the requested product and exposed the next scheduling risk.";
                case "software-team/product-owner" -> "Refined product scope for the requested product and clarified the next decision.";
                case "software-team/application-developer" -> "Reviewed delivery work for the requested product and identified the next engineering move.";
                default -> "Synthetic test response.";
            };
        }
        return LlmJobResult.succeeded(job, "mock", content, content, startedAt);
    }

    private String directorAssignmentsJson() {
        try {
            Map<String, String> assignments = new LinkedHashMap<>();
            for (TeamRoleDefinition role : teamRoleRegistry.allRoles()) {
                assignments.put(role.roleId(), role.capabilitySummary());
            }
            return objectMapper.writeValueAsString(assignments);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build mock director assignments", e);
        }
    }

    private static int extractIteration(String prompt) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Cycle count: (\\d+)").matcher(prompt);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 1;
    }
}
