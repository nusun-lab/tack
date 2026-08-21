package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceCoordinationToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;

@ApplicationScoped
public class ProjectManagerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/project-manager";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Project Manager")
                .main("""
                        Complete Project Manager cycle, including the focused bootstrap, coordination, and task-planning
                        behaviors when they become applicable. Establish and maintain the delivery workspace, consume PRODUCT READY
                        product slices, decompose them into the small assigned implementation tasks needed to deliver the outcome,
                        sequence dependencies, coordinate blockers and review, and bridge completed implementation back to product
                        acceptance. New product work originates with product ownership; executable work is derived and managed here.
                        """, "direct:example-software-team-project-manager")
                .subroutine(
                        "bootstrap",
                        "Establish only the shared project-management, documentation, source-control, team-chat, access, and navigation foundation required for delivery; this produces workspace infrastructure, not product backlog items or implementation tasks.",
                        "direct:example-software-team-project-manager-bootstrap")
                .subroutine(
                        "coordination",
                        "Operate an established delivery workspace with active implementation work or PRODUCT READY product slices; maintain decomposition, assignment, blockers, review, integration readiness, and durable delivery status.",
                        "direct:example-software-team-project-manager-coordination")
                .subroutine(
                        "task-planning",
                        "Decompose one PRODUCT READY product slice into the small assigned implementation tasks needed to make the whole slice actionable, with explicit dependencies and review evidence.",
                        "direct:example-software-team-project-manager-task-planning")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-project-manager")
                .routeId("example-software-team-project-manager")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                        .to("direct:pm-project-bootstrap")
                .end()
                .to("direct:pm-coordination-loop")
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-bootstrap")
                .routeId("example-software-team-project-manager-bootstrap")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                        .to("direct:pm-project-bootstrap")
                .end()
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                        .to("direct:pm-team-onboarding")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-coordination")
                .routeId("example-software-team-project-manager-coordination")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                        .to("direct:pm-coordination-loop")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-task-planning")
                .routeId("example-software-team-project-manager-task-planning")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo(true))
                        .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Focused delivery-planning pass: one PRODUCT READY product slice, its existing implementation
                            children, remaining decomposition, owners, priorities, dependencies, repositories, acceptance page,
                            risks, and next executable steps.
                            """))
                        .to("tack:thought-enter?name=pm-cycle&maxTokens=300")
                        .to("direct:pm-documentation-status-sync")
                        .to("direct:pm-team-onboarding")
                        .to("direct:pm-work-intake-loop")
                        .choice()
                            .when(exchangeProperty("decision.pm-has-ready-product-handoff.result").isEqualTo(true))
                                .to("direct:pm-task-planning-loop")
                        .end()
                        .to("direct:pm-team-sync")
                        .to("direct:pm-cycle-closeout")
                        .to("tack:thought-exit?name=pm-cycle")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:pm-project-manager-root-enter")
                .routeId("pm-project-manager-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable delivery anchors: documentation index/path, project-basics/current-work pages,
                        team chat, project-management workspace, and source repository.
                        """))
                .to("tack:thought-enter?name=project-manager-routine&maxTokens=500");

        from("direct:pm-project-manager-root-exit")
                .routeId("pm-project-manager-root-exit")
                .to("tack:thought-exit?name=project-manager-routine");

        from("direct:pm-project-basics-check")
                .routeId("pm-project-basics-check")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Verify project basics without mutation using the capabilities available to you.
                                Check only direct anchors from active thoughts, documentation index/project-basics links,
                                and the minimal project-management or team-chat lookup needed to confirm missing basics.
                                Capture bootstrap status, team chat, project-management workspace, source repository under
                                agent-workspace, documentation repository, and documentation navigation. Do not scan whole
                                projects, repositories, documentation repositories, or channels.
                                """))
                .to("tack:act?id=pm-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Are project basics verified and current enough for delivery coordination, with Git organization
                                agent-workspace and working links for team chat, project management, documentation repository,
                                source repository, and documentation navigation?
                                """))
                .to("tack:decide?id=collaboration-setup-complete");

        from("direct:pm-project-bootstrap")
                .routeId("pm-project-bootstrap")
                .setProperty("collaborationSetupAttempt", constant(0))
                .loopDoWhile(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo(true))
                    .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current bootstrap checklist: project-management workspace, team chat, team membership,
                        repositories, and documentation navigation. Bootstrap establishes workspace infrastructure only;
                        product backlog creation and implementation-task creation are outside this flow.
                        Promote final anchors before bootstrap exits.
                        """))
                    .to("tack:thought-enter?name=pm-bootstrap&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using the available project-management capability, search direct project names first,
                                    then create or confirm exactly one shared project workspace. Preserve an existing useful
                                    project. Stop after the project workspace is verified. Do not create product backlog items
                                    or implementation tasks during bootstrap.
                                    """))
                    .to("tack:act?id=plane-project-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using source control, search direct repository names first, then create or confirm exactly
                                    one markdown documentation repository under Git organization agent-workspace. Never use a
                                    personal namespace and do not create branches, pull requests, tags, releases, issues, labels,
                                    milestones, or time tracking.
                                    """))
                    .to("tack:act?id=documentation-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using source control, search direct repository names first, then create or confirm exactly
                                    one target source repository under Git organization agent-workspace. Never use a personal
                                    namespace and do not create branches, pull requests, tags, releases, issues, labels,
                                    milestones, or time tracking.
                                    """))
                    .to("tack:act?id=source-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using team chat, create or confirm one private project channel. Invite the current team
                                    and David Gordon (@aphelio). Never create a duplicate channel. Do not post setup status yet.
                                    """))
                    .to("tack:act?id=chat-channel-setup&toolProfile=" + TeamChatToolProfile.ID)
                    .to("direct:pm-team-onboarding")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Create or update the canonical documentation bootstrap pages.
                                    Index: navigation only, linking project-basics.
                                    Project-basics: mission, Git organization agent-workspace, documentation repository URL,
                                    team chat, project-management workspace, source repository, and stable role directory/responsibilities.
                                    Do not document a supposedly current active-run roster; current staffing comes from mission roster context.
                                    Before marking any project basic missing, search active thoughts for a verified current-mission anchor.
                                    """))
                    .to("tack:act?id=documentation-project-index&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Read only the documentation index and project-basics page. Verify every project basic has
                                    a working link. Confirm documentation and source repositories are under agent-workspace,
                                    not a personal namespace. Before marking any project basic missing, search active thoughts
                                    for a verified current-mission anchor for that artifact.
                                    """))
                    .to("tack:act?id=documentation-bootstrap-audit&toolProfile=" + SourceControlToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Using team chat, read recent project conversation. If someone asked about setup, answer
                                    that thread. Otherwise post only if basics are missing, stale, or blocked. Keep chat to a
                                    concise pointer; durable project-management and source-control state are authoritative.
                                    Include the project-basics documentation link.
                                    """))
                    .to("tack:act?id=collaboration-setup-message&toolProfile=" + TeamChatToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    After this bootstrap pass, does the documentation index and project-basics page link
                                    Git organization agent-workspace, team chat, project-management workspace, documentation
                                    repository, source repository, and documentation navigation?
                                    """))
                    .to("tack:decide?id=collaboration-setup-complete")
                    .to("tack:thought-exit?name=pm-bootstrap")
                .end();

        from("direct:pm-coordination-loop")
                .routeId("pm-coordination-loop")
                .setProperty("projectManagerCycle", constant(0))
                .setProperty("decision.project-manager-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.project-manager-done.result").isNotEqualTo(true))
                    .setProperty("projectManagerCycle", simple("${exchangeProperty.projectManagerCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Current delivery pass: selected PRODUCT READY product slice, decomposition state, selected implementation
                        task, review/integration state, owners, and risks.
                        """))
                    .to("tack:thought-enter?name=pm-cycle&maxTokens=300")
                    .to("direct:pm-documentation-status-sync")
                    .to("direct:pm-team-onboarding")
                    .to("direct:pm-active-delivery-check")
                    .to("direct:pm-work-intake-loop")
                    .choice()
                        .when(exchangeProperty("decision.pm-has-ready-product-handoff.result").isEqualTo(true))
                            .to("direct:pm-task-planning-loop")
                    .end()
                    .to("direct:pm-team-sync")
                    .to("direct:pm-cycle-closeout")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is this delivery pass locally complete because collaboration basics are established and
                                    one of these occurred: active review, integration, or blocker work was coordinated; one PRODUCT READY
                                    product slice was adequately decomposed into assigned actionable implementation work; no PRODUCT READY
                                    product slice exists and that was clearly determined; a completed slice was returned for product
                                    acceptance; or one exact product-input, assignment, or infrastructure blocker was recorded?
                                    Do not wait for another role to consume this output.
                                    """))
                    .to("tack:decide?id=project-manager-done")
                    .to("tack:thought-exit?name=pm-cycle")
                .end();

        from("direct:pm-documentation-status-sync")
                .routeId("pm-documentation-status-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read only current mission status anchors. Use documentation index/current-work links,
                                directly linked project-management items, and recent team-chat handoff context only if needed.
                                Capture owners, work-item links, canonical documentation pages, agent-workspace repository links,
                                or broken navigation. Do not sweep unrelated work.
                                """))
                .to("tack:act?id=pm-workspace-status-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:pm-active-delivery-check")
                .routeId("pm-active-delivery-check")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Active delivery coordination: one highest-priority implementation item in Review, Blocked, or In Progress;
                        REVIEW READY, INTEGRATION READY, merged integration evidence, BLOCKED evidence, parent product slice,
                        owner, status decision, and next coordination action.
                        """))
                .to("tack:thought-enter?name=pm-active-delivery&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Inspect at most one highest-priority active implementation item, in this order: Review,
                                Blocked, then In Progress. Use minimal direct project-management evidence and linked source-control evidence.
                                For Review with REVIEW READY, verify the implementation against its referenced acceptance, changed files,
                                validation evidence, and branch/commit/pull request. When implementation review is accepted but an open
                                pull request still requires integration, leave the task in Review and add or refresh INTEGRATION READY
                                with the pull request and reviewed evidence. Once integration is verified as merged, move the implementation
                                task to Done. When all implementation tasks derived from the same PRODUCT READY product slice are Done,
                                add or refresh PRODUCT ACCEPTANCE READY on that product slice with the canonical acceptance page and
                                completed implementation evidence. For Blocked, identify the required owner and add PRODUCT INPUT REQUIRED
                                only for a product decision; otherwise record the exact coordination action without inventing a solution.
                                Do not create duplicate replacement tasks for active, review, or blocked work.
                                """))
                .to("tack:act?id=pm-active-delivery-check&toolProfile=" + ProjectManagementToolProfile.ID)
                .to("tack:thought-exit?name=pm-active-delivery");

        from("direct:pm-team-onboarding")
                .routeId("pm-team-onboarding")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Keep team access current for the verified project. Compare current mission/team members with
                                project-management workspace members and team-chat channel members. Add every missing teammate
                                where supported; remove nobody unless the mission clearly says they left. Note source-control
                                access gaps, but do not block delivery coordination on repository membership.
                                """))
                .to("tack:act?id=pm-team-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID);

        from("direct:pm-work-intake-loop")
                .routeId("pm-work-intake-loop")
                .setProperty("decision.pm-has-ready-product-handoff.result", constant(false))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One PRODUCT READY product slice under review: work-item id/link, priority, canonical acceptance page,
                        Gherkin acceptance, existing implementation children, readiness content, and requested next delivery action.
                        Promote the selected item before intake exits.
                        """))
                .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Determine whether exactly one delivery-ready product slice exists in the canonical product backlog.
                                Select one only when it contains a current PRODUCT READY comment and its canonical page contains usable
                                scope and testable Gherkin acceptance. New product work must originate in the product backlog; do not
                                create product items here. Capture the product item, priority, acceptance page, Gherkin scenarios,
                                existing implementation tasks, unresolved product questions, and requested next delivery action.
                                If no ready product slice exists, do not invent acceptance criteria or create implementation work.
                                """))
                .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Does one current PRODUCT READY product slice exist with usable scope and testable Gherkin acceptance
                                for delivery decomposition? Return false when no such delivery-ready product slice exists.
                                """))
                .to("tack:decide?id=pm-has-ready-product-handoff")
                .to("tack:thought-exit?name=pm-intake");

        from("direct:pm-task-planning-loop")
                .routeId("pm-task-planning-loop")
                .setProperty("pmTaskPlanningCycle", constant(0))
                .setProperty("decision.pm-task-planning-done.result", constant(false))
                .loopDoWhile(exchangeProperty("decision.pm-task-planning-done.result").isNotEqualTo(true))
                    .setProperty("pmTaskPlanningCycle", simple("${exchangeProperty.pmTaskPlanningCycle++}"))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Decomposition of the selected PRODUCT READY product slice: existing implementation children,
                        uncovered acceptance behavior, assignees, repositories, dependencies, next executable steps,
                        and expected review evidence.
                        """))
                    .to("tack:thought-enter?name=pm-planning&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Review the selected PRODUCT READY product slice and implementation tasks already derived from it.
                                    Decompose by delivery responsibility, dependencies, and independently finishable technical outcomes.
                                    Create or refine exactly one missing or oversized implementation leaf per pass. A trivial product slice
                                    may need one implementation task; a larger slice may need several. Every ready leaf must remain linked
                                    to the parent product slice and canonical Gherkin acceptance, have a named assignee, priority, source
                                    repository, dependencies or explicit independence, one concrete next code step, expected review evidence,
                                    and a comment beginning exactly DELIVERY READY. Do not duplicate existing coverage, invent product scope,
                                    or stop after one task merely because one actionable leaf exists.
                                    """))
                    .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                    .setHeader(TackHeaders.PROMPT, constant("""
                                    Is the selected PRODUCT READY product slice adequately decomposed for delivery because all required
                                    implementation work is represented by small assigned DELIVERY READY tasks with clear dependencies and
                                    together covers the product acceptance, or because one exact missing product input, assignment, or
                                    infrastructure blocker was durably recorded? Return false when acceptance behavior remains uncovered
                                    or an implementation item is still too broad to be independently actionable.
                                    """))
                    .to("tack:decide?id=pm-task-planning-done")
                    .to("tack:thought-exit?name=pm-planning")
                .end();

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Read recent project chat and participate when a real coordination exchange helps.
                                Reply to direct questions, review/blocker handoffs, or ask one named question that unblocks work.
                                Avoid generic status; link the project-management item or product documentation when useful.
                                """))
                .to("tack:act?id=pm-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-cycle-closeout")
                .routeId("pm-cycle-closeout")
                .setHeader(TackHeaders.PROMPT, constant("""
                                Curate one small product documentation improvement only when it helps navigation or understanding.
                                Keep daily work state in project management, leave ephemeral chat out, and split pages before they
                                grow past a readable size.
                                """))
                .to("tack:act?id=pm-status-note&toolProfile=" + SourceControlToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                                Update one project-management work item only if verified status, risk, review requirement,
                                PRODUCT INPUT REQUIRED, DELIVERY READY, REVIEW READY, INTEGRATION READY, PRODUCT ACCEPTANCE READY,
                                or BLOCKED changed. Do not depend on custom states or labels.
                                """))
                .to("tack:act?id=pm-release-risk-update&toolProfile=" + ProjectManagementToolProfile.ID);
    }
}
