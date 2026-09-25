package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.ProjectManagementToolProfile;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.example.tool.SourceControlWriteToolProfile;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceCoordinationToolProfile;
import us.nusun.llmshare.example.tool.WorkspaceReadonlyToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

@ApplicationScoped
public class ProjectManagerRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/project-manager";

    @Override
    public List<RoutineDefinition> routines() { return List.of(routine()); }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Project Manager")
                .main("Coordinate delivery from setup through completion.", "direct:example-software-team-project-manager")
                .subroutine("bootstrap", "Establish the shared delivery workspace.", "direct:example-software-team-project-manager-bootstrap")
                .subroutine("coordination", "Coordinate active delivery and team dependencies.", "direct:example-software-team-project-manager-coordination")
                .subroutine("task-planning", "Decompose product outcomes into implementation work.", "direct:example-software-team-project-manager-task-planning")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-software-team-project-manager")
                .routeId("example-software-team-project-manager")
                .to("direct:pm-project-manager-root-enter")
                .setProperty("decision.project-manager-mission-complete.result", constant("no"))
                .loopDoWhile(exchangeProperty("decision.project-manager-mission-complete.result").isNotEqualTo("yes"))
                    .to("direct:pm-orientation")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Choose exactly one highest-priority Project Manager focus for the next focused pass. Operating
                            model: PO stays ahead of PM with several ready work:product slices; PM stays ahead of DEV with
                            several work:implementation children; DEV normally carries only one active/unmerged source unit.
                            Developer WIP=1 does NOT mean planning WIP=1.
                            
                            ACCESS/LIVENESS INVARIANT: the CURRENT MISSION ROSTER is authoritative for who is allowed and
                            expected to participate now. Preserve the most recent successfully reconciled roster in thought.
                            If the current roster contains a teammate not present in that last reconciled roster, onboarding
                            is immediately actionable and should outrank ordinary delivery/planning. Do not wait for the new
                            teammate to report a 403 or chat blocker. When the roster has not changed and recent
                            project/channel membership was verified, do not re-prove all membership every pass; recheck only
                            on concrete access-drift evidence or a contradictory read, and verify one contradictory empty
                            result once before treating it as real drift.
                            
                            Allowed focus values, in priority order when genuinely actionable:
                            - bootstrap: any shared Plane/source/docs/chat foundation basic is missing or unusable. An
                              existing empty source repository is a valid foundation artifact.
                            - onboarding: the foundation exists and the CURRENT MISSION ROSTER differs from the last
                              successfully reconciled roster, or concrete evidence shows a current teammate lost Plane
                              project/channel access. A new roster member is enough to route
                              here without waiting for them to fail first.
                            - active-delivery: a merged/open-PR reconciliation, topology finding, real blocker, dependency
                              promotion/repair, cancelled coverage, product-question return, or completed-parent handoff can
                              be acted on now. PM does not issue a magic approval lease before Director integration. Do not
                              manufacture a blocker from an empty repo, missing main branch, speculative Gitea permission,
                              or missing Mattermost handoff when Plane already has Todo + Developer assignee.
                            - planning: usable work:product exists and the assigned executable work:implementation Todo
                              backlog is below a useful buffer, normally about 3-5 actionable children, or future
                              dependency-blocked work:implementation should be represented in Backlog. An active Developer
                              task or open PR is NOT a reason to stop planning.
                            - coordination: there is a useful message to initiate, or a fresh team
                              exchange needs Project Manager participation.
                            - documentation: a concrete durable navigation/status curation need
                              exists; daily work state belongs in Plane.
                            - wait: no focus above is currently useful. A shallow Developer backlog while more work:product
                              can be safely decomposed is actionable planning work, not a reason to wait.
                            
                            Fresh orientation evidence outranks older HOLD/wait conclusions. Plane Todo + Developer assignee
                            is the complete PM -> DEV handoff; Developer owns Todo -> In
                            Progress. Choose exactly one allowed focus value.
                            """))
                    .to("tack:decide?id=pm-focus&choices=bootstrap,onboarding,active-delivery,planning,coordination,documentation,wait")
                    .toD("direct:pm-focus-${exchangeProperty.decision.pm-focus.result}")
                .end()
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:pm-focus-bootstrap")
                .routeId("pm-focus-bootstrap")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused workspace bootstrap until shared collaboration basics are usable or
                        no further bootstrap progress is possible.
                        """))
                .to("tack:thought-enter?name=pm-bootstrap-focus&maxTokens=350")
                .to("direct:pm-bootstrap-focus-dispatch")
                .to("tack:thought-exit?name=pm-bootstrap-focus");

        from("direct:pm-focus-onboarding")
                .routeId("pm-focus-onboarding")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused roster-delta access alignment: reconcile newly active or actually drifted teammates into
                        the shared workspace, then remember the reconciled roster.
                        """))
                .to("tack:thought-enter?name=pm-onboarding-focus&maxTokens=250")
                .to("direct:pm-team-onboarding")
                .to("tack:thought-exit?name=pm-onboarding-focus");

        from("direct:pm-focus-active-delivery")
                .routeId("pm-focus-active-delivery")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused delivery supervision: reconcile one current blocker, topology/dependency issue,
                        merged/open-PR consequence, product return, or
                        parent-delivery outcome while useful dispositions remain.
                        """))
                .to("tack:thought-enter?name=pm-active-delivery-focus&maxTokens=400")
                .to("direct:pm-active-delivery-focus")
                .to("tack:thought-exit?name=pm-active-delivery-focus");

        from("direct:pm-focus-planning")
                .routeId("pm-focus-planning")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused delivery planning: create/refine one work:implementation child per act, repeating until
                        the executable Developer Todo buffer is normally about 3-5 or safe planning genuinely cannot
                        continue. Dependency-blocked future children belong in Backlog rather than being omitted.
                        """))
                .to("tack:thought-enter?name=pm-planning-focus&maxTokens=400")
                .to("direct:pm-planning-focus")
                .to("tack:thought-exit?name=pm-planning-focus");

        from("direct:pm-focus-coordination")
                .routeId("pm-focus-coordination")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused Project Manager coordination for one useful initiated
                        notification/question/handoff or one fresh actionable exchange.
                        """))
                .to("tack:thought-enter?name=pm-coordination-focus&maxTokens=250")
                .to("direct:pm-team-sync")
                .to("tack:thought-exit?name=pm-coordination-focus");

        from("direct:pm-focus-documentation")
                .routeId("pm-focus-documentation")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Focused durable workspace/navigation curation; current work state remains in the work system.
                        """))
                .to("tack:thought-enter?name=pm-documentation-focus&maxTokens=250")
                .to("direct:pm-documentation-status-sync")
                .to("tack:thought-exit?name=pm-documentation-focus");

        from("direct:pm-focus-wait")
                .routeId("pm-focus-wait")
                .to("direct:pm-completion-focus")
                .choice()
                    .when(exchangeProperty("decision.project-manager-mission-complete.result").isNotEqualTo("yes"))
                        .delay(120000)
                .end();

        from("direct:pm-orientation")
                .routeId("pm-orientation")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read the smallest fresh current-mission slice needed to rank PM's next focus. Compare the
                        CURRENT MISSION ROSTER to the last successfully reconciled roster retained in thought. A newly
                        present teammate is a high-confidence onboarding signal by itself. If roster is unchanged and
                        recent access was verified, do not enumerate workspace/project/channel membership again unless
                        there is actual access-drift evidence. If a membership read unexpectedly contradicts recent
                        verified membership, repeat one narrow direct read before declaring drift.
                        
                        Also capture shared basics; one actionable
                        blocker/topology/dependency/merged-work/product-return signal; usable work:product; assigned
                        executable work:implementation Todo depth; useful dependency-blocked work:implementation; one
                        useful message worth initiating or fresh coordination signal; concrete docs/navigation drift. Count work by classification label, not wording. A sparse
                        projection that omits labels/parent is unknown, not negative evidence. If thought already
                        contains verified project/repo/channel ids, use direct lookups instead of listing whole
                        workspaces. Report current observations only. Do not recommend a next focus, invent sequencing
                        policy, preserve an older HOLD conclusion against newer
                        evidence, mutate, or solve the selected focus here.
                        """))
                .to("tack:act?id=pm-orientation&toolProfile=" + WorkspaceReadonlyToolProfile.ID);

        from("direct:pm-bootstrap-focus-dispatch")
                .routeId("pm-bootstrap-focus-dispatch")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo("yes"))
                        .to("direct:pm-project-bootstrap")
                .end();

        from("direct:pm-active-delivery-focus")
                .routeId("pm-active-delivery-focus")
                .setProperty("pmFocusedDeliveryIterations", constant(0))
                .setProperty("pmFocusedDeliveryContinue", constant(true))
                .loopDoWhile(simple("${exchangeProperty.pmFocusedDeliveryContinue} == true && ${exchangeProperty.pmFocusedDeliveryIterations} < 6"))
                    .setProperty("pmFocusedDeliveryIterations", simple("${exchangeProperty.pmFocusedDeliveryIterations++}"))
                    .setProperty("pmFocusedDeliveryContinue", constant(false))
                    .to("direct:pm-active-delivery-check")
                    .choice()
                        .when(exchangeProperty("act.pm-active-delivery-update.durableProgress").isEqualTo(true))
                            .setProperty("pmFocusedDeliveryContinue", constant(true))
                    .end()
                .end();

        from("direct:pm-planning-focus")
                .routeId("pm-planning-focus")
                .setProperty("pmFocusedPlanningIterations", constant(0))
                .setProperty("pmFocusedPlanningContinue", constant(true))
                .setProperty("decision.pm-dev-backlog-deep-enough.result", constant("no"))
                .loopDoWhile(simple("${exchangeProperty.pmFocusedPlanningContinue} == true && ${exchangeProperty.pmFocusedPlanningIterations} < 5"))
                    .setProperty("pmFocusedPlanningIterations", simple("${exchangeProperty.pmFocusedPlanningIterations++}"))
                    .setProperty("pmFocusedPlanningContinue", constant(false))
                    .to("direct:pm-work-intake")
                    .choice()
                        .when(exchangeProperty("decision.pm-has-product-for-delivery.result").isEqualTo("yes"))
                            .to("direct:pm-task-planning-loop")
                            .choice()
                                .when(exchangeProperty("pmTaskPlanningMadeProgress").isEqualTo(true))
                                    .setProperty("pmFocusedPlanningContinue", constant(true))
                            .end()
                    .end()
                    .to("direct:pm-dev-backlog-depth-check")
                    .choice()
                        .when(exchangeProperty("decision.pm-dev-backlog-deep-enough.result").isEqualTo("yes"))
                            .setProperty("pmFocusedPlanningContinue", constant(false))
                        .otherwise()
                            .setProperty("pmFocusedPlanningContinue", constant(true))
                    .end()
                .end();

        from("direct:pm-dev-backlog-depth-check")
                .routeId("pm-dev-backlog-depth-check")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only current work:implementation queue depth in Plane. Count assigned,
                        dependency-satisfied, actionable Todo children carrying work:implementation. Never count
                        work:product. Also note useful planned work:implementation in Backlog whose only blocker is an
                        explicit unfinished dependency, and whether more usable work:product parents can safely be
                        decomposed. Developer active WIP/open PR does not reduce desired planning depth. If labels are
                        absent, read classification instead of guessing from
                        title/description. Do not mutate or inspect source.
                        """))
                .to("tack:act?id=pm-dev-backlog-depth-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is PM sufficiently ahead of Developer for now? Normally require about 3-5 assigned,
                        dependency-satisfied actionable Todo work:implementation children when enough safe work:product
                        exists. Answer no when usable work:product can be decomposed but that executable queue is
                        shallower, even if Developer already has active WIP or an open PR. Explicit dependency-blocked
                        work:implementation in Backlog is healthy planning but does not count as executable Todo depth.
                        Never count work:product items. Answer yes with a shallower queue only when product information,
                        genuine dependency topology across all available product parents, or remaining mission size
                        makes further safe executable planning inappropriate.
                        """))
                .to("tack:decide?id=pm-dev-backlog-deep-enough");

        from("direct:pm-completion-focus")
                .routeId("pm-completion-focus")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Review current mission completion once using minimal authoritative evidence. One unfinished
                        work:product parent, work:implementation child, current unresolved blocker, merge, product
                        question, topology finding, acceptance follow-up, or current-roster access gap means incomplete.
                        Comments are evidence/context, never hidden workflow state. Do not mutate.
                        """))
                .to("tack:act?id=pm-mission-status-review&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is the mission complete from Project Management's perspective? Answer yes only when all required
                        work:product parents are Done, the current roster is reconciled to required project/channel
                        access, and no current unresolved work:implementation delivery, blocker, PRODUCT QUESTION,
                        integration, assignment, topology investigation/finding, or acceptance follow-up remains. Ignore
                        historical comments superseded by newer durable state/evidence.
                        """))
                .to("tack:decide?id=project-manager-mission-complete");

        from("direct:example-software-team-project-manager-bootstrap")
                .routeId("example-software-team-project-manager-bootstrap")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isNotEqualTo("yes"))
                        .to("direct:pm-project-bootstrap")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-coordination")
                .routeId("example-software-team-project-manager-coordination")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo("yes"))
                        .to("direct:pm-team-onboarding")
                        .to("direct:pm-coordination-loop")
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:example-software-team-project-manager-task-planning")
                .routeId("example-software-team-project-manager-task-planning")
                .to("direct:pm-project-manager-root-enter")
                .to("direct:pm-project-basics-check")
                .choice()
                    .when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo("yes"))
                        .to("direct:pm-team-onboarding")
                        .to("direct:pm-work-intake")
                        .choice()
                            .when(exchangeProperty("decision.pm-has-product-for-delivery.result").isEqualTo("yes"))
                                .to("direct:pm-task-planning-loop")
                        .end()
                .end()
                .to("direct:pm-project-manager-root-exit");

        from("direct:pm-project-manager-root-enter")
                .routeId("pm-project-manager-root-enter")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable delivery anchors: verified project/repo/channel ids, last successfully reconciled
                        mission roster, selected work:product parent, work:implementation queue depth/coverage, product
                        questions, dependency topology, source/PR evidence, blockers,
                        merged-work reconciliation, and team chat.
                        """))
                .to("tack:thought-enter?name=project-manager-routine&maxTokens=500");

        from("direct:pm-project-manager-root-exit")
                .routeId("pm-project-manager-root-exit")
                .to("tack:thought-exit?name=project-manager-routine");

        from("direct:pm-project-basics-check")
                .routeId("pm-project-basics-check")
                .setProperty("decision.collaboration-setup-complete.result", constant("no"))
                .setHeader(TackHeaders.PROMPT, constant("""
                        Verify only the shared foundation needed to enter coordination: exactly one current-mission
                        Plane project, documentation repository/navigation, source repository under agent-workspace, and
                        mission team-chat channel, all accessible to this Project Manager run. Prefer direct lookup by
                        verified ids already in thought. An existing empty source repository is valid foundation; do not
                        require main/default branch, commits, or application code.
                        
                        Foundation existence and teammate onboarding are separate. During bootstrap, add every teammate
                        already present in the CURRENT MISSION ROSTER. Future teammates are reconciled when they appear
                        in the roster. Do not call someone onboarded merely because
                        their workspace account exists. Do not mutate.
                        """))
                .to("tack:act?id=pm-workspace-basics-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Are Plane, source repo, docs/navigation, and mission chat verified and readable by this Project
                        Manager run? Treat an existing empty source repo as valid. Answer yes based on foundation
                        existence/readability only; roster access is handled separately.
                        """))
                .to("tack:decide?id=collaboration-setup-complete");

        from("direct:pm-project-bootstrap")
                .routeId("pm-project-bootstrap")
                .setProperty("collaborationSetupAttempt", constant(0))
                .setProperty("pmBootstrapMadeProgress", constant(true))
                .loopDoWhile(simple(
                        "${exchangeProperty.decision.collaboration-setup-complete.result} != 'yes' && "
                                + "${exchangeProperty.pmBootstrapMadeProgress} == true && "
                                + "${exchangeProperty.collaborationSetupAttempt} < 6"))
                    .setProperty("collaborationSetupAttempt", simple("${exchangeProperty.collaborationSetupAttempt++}"))
                    .setProperty("pmBootstrapMadeProgress", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Bootstrap only: Plane project, source/docs repositories, mission chat, navigation, immediate
                            access for current-roster teammates, and one useful kickoff notification.
                            Product work is not a bootstrap prerequisite.
                            """))
                    .to("tack:thought-enter?name=pm-bootstrap&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Create or confirm exactly one current-mission Plane project. Preserve an existing useful
                            project. Then reconcile project membership against the CURRENT MISSION ROSTER using actual Plane
                            evidence: for each current-roster Plane identity, resolve the matching Plane workspace member,
                            compare it with the project's current member list, and add the resolved teammate when missing.
                            Keep mission roster, workspace membership, and project membership as separate sets. Report only
                            verified membership facts. Do not add hypothetical/future personas, remove nobody, and do
                            not create product or implementation work.
                            """))
                    .to("tack:act?id=plane-project-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .choice().when(exchangeProperty("act.plane-project-provisioning.durableProgress").isEqualTo(true))
                        .setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Create or confirm exactly one markdown documentation repository under agent-workspace. Do
                            not create planned work in source control.
                            """))
                    .to("tack:act?id=documentation-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .choice().when(exchangeProperty("act.documentation-repository-provisioning.durableProgress").isEqualTo(true))
                        .setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Create or confirm exactly one target source repository under agent-workspace. An empty
                            repository is valid; PM does not need to seed main or application content.
                            """))
                    .to("tack:act?id=source-repository-provisioning&toolProfile=" + WorkspaceCoordinationToolProfile.ID)
                    .choice().when(exchangeProperty("act.source-repository-provisioning.durableProgress").isEqualTo(true))
                        .setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Create or confirm one private current-mission team-chat channel accessible to Project
                            Management. Never create a duplicate channel. Reconcile that channel against the CURRENT MISSION
                            ROSTER using actual current channel membership and add every current teammate who is missing
                            when supported. Do not infer channel membership from Plane membership or from an older roster.
                            Do not add hypothetical/future personas and remove nobody. After the channel is usable, initiate
                            one concise kickoff message linking the authoritative Plane project and source/docs repositories
                            known so far and telling current teammates the workspace is ready for their role. Do not wait
                            for someone else to speak first. Later roster changes are handled immediately by onboarding.
                            """))
                    .to("tack:act?id=chat-channel-setup&toolProfile=" + TeamChatToolProfile.ID)
                    .choice().when(exchangeProperty("act.chat-channel-setup.durableProgress").isEqualTo(true))
                        .setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Create or update compact canonical navigation/project-basics documentation linking mission,
                            repositories, Plane project, team chat, and role responsibilities.
                            """))
                    .to("tack:act?id=documentation-project-index&toolProfile=" + SourceControlWriteToolProfile.ID)
                    .choice().when(exchangeProperty("act.documentation-project-index.durableProgress").isEqualTo(true))
                        .setProperty("pmBootstrapMadeProgress", constant(true)).end()
                    .to("direct:pm-project-basics-check")
                    .choice().when(exchangeProperty("decision.collaboration-setup-complete.result").isEqualTo("yes"))
                        .setProperty("pmBootstrapMadeProgress", constant(false)).end()
                    .to("tack:thought-exit?name=pm-bootstrap")
                .end();

        from("direct:pm-coordination-loop")
                .routeId("pm-coordination-loop")
                .to("direct:pm-team-onboarding")
                .setProperty("pmProductiveIterations", constant(0))
                .setProperty("pmMadeProgress", constant(true))
                .loopDoWhile(simple("${exchangeProperty.pmMadeProgress} == true && ${exchangeProperty.pmProductiveIterations} < 10"))
                    .setProperty("pmProductiveIterations", simple("${exchangeProperty.pmProductiveIterations++}"))
                    .setProperty("pmMadeProgress", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Current delivery pass: one actionable real
                            blocker/dependency/topology/merged-work/product-return item, then one work:product parent
                            needing decomposition. PM planning should remain ahead of Developer execution.
                            """))
                    .to("tack:thought-enter?name=pm-cycle&maxTokens=300")
                    .to("direct:pm-active-delivery-check")
                    .choice().when(exchangeProperty("act.pm-active-delivery-update.durableProgress").isEqualTo(true))
                        .setProperty("pmMadeProgress", constant(true)).end()
                    .to("direct:pm-work-intake")
                    .choice()
                        .when(exchangeProperty("decision.pm-has-product-for-delivery.result").isEqualTo("yes"))
                            .to("direct:pm-task-planning-loop")
                            .choice().when(exchangeProperty("pmTaskPlanningMadeProgress").isEqualTo(true)).setProperty("pmMadeProgress", constant(true)).end()
                    .end()
                    .to("tack:thought-exit?name=pm-cycle")
                .end()
                .to("direct:pm-team-sync");

        from("direct:pm-team-onboarding")
                .routeId("pm-team-onboarding")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Reconcile CURRENT MISSION ROSTER access now. The roster is the authorization boundary.
                        
                        Use the previous successful reconciled-roster memory only to detect a delta; use current
                        Plane/Mattermost evidence for the actual mutation. For EACH teammate in the CURRENT MISSION
                        ROSTER: resolve their Plane workspace identity, verify/add project membership, resolve their
                        Mattermost identity, and verify/add mission-channel membership. Do not add personas
                        absent from the current roster. Remove nobody.
                        
                        Do not wait for a newly started teammate to announce an access blocker. If the roster changed,
                        proactively reconcile them now. If roster did not change and a single membership read
                        unexpectedly returns empty/contradictory results, repeat one narrow direct membership read
                        before declaring drift; an actual 401/403 from the teammate is immediate drift evidence.
                        
                        After successful reconciliation, report the exact CURRENT MISSION ROSTER as the last
                        successfully reconciled roster in durable thought so future passes can detect newly added
                        teammates cheaply. Send one concise onboarding notification only
                        when it helps the new teammate act sooner.
                        """))
                .to("tack:act?id=pm-team-onboarding&toolProfile=" + WorkspaceCoordinationToolProfile.ID);

        from("direct:pm-active-delivery-check")
                .routeId("pm-active-delivery-check")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One delivery-maintenance item: topology finding/application, merged/open-PR reconciliation,
                        actionable real blocker/product question, dependency promotion/repair, cancelled coverage, or
                        completed-parent handoff. Comments provide evidence/context, not workflow state.
                        """))
                .to("tack:thought-enter?name=pm-active-delivery&maxTokens=300")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read at most one highest-value ACTIONABLE current delivery item using fresh Plane and linked
                        source evidence. Priority: (1) current topology finding needing PM disposition; (2)
                        work:implementation child whose linked PR has MERGED but Plane child is not yet Done; (3)
                        actionable blocker based on a real failed operation, malformed context, product question, or
                        external condition PM can route/repair; (4) Backlog work:implementation whose explicit
                        dependencies may now be satisfied or invalid; (5) Cancelled implementation coverage that remains
                        required; (6) In Progress work:product parent whose required implementation children may now be
                        terminally dispositioned; (7) an open-PR implementation child only when PM has a concrete
                        blocker/dependency/product-context issue to repair.
                        Director owns normal safe PR integration directly.
                        
                        Do NOT treat these as blockers: an accessible empty source repo, absent main/default branch
                        before first implementation, speculative/unverified Gitea push permission, lack of a PM
                        Mattermost assignment message, or a Todo work:implementation already assigned to Developer. Todo
                        + Developer assignment is already the handoff; Developer claims
                        it In Progress. Do not mutate in this read.
                        """))
                .to("tack:act?id=pm-active-delivery-evidence-read&toolProfile=" + WorkspaceReadonlyToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Update only the item already read. Use Plane real states and concise evidence/context comments;
                        never manufacture REVIEW READY, INTEGRATION READY, PRODUCT ACCEPTANCE READY, or similar hidden
                        workflow states. For a verified merged work:implementation PR, move that child In Progress ->
                        Done and retain concise merge/validation evidence. Promote dependency-satisfied Backlog
                        work:implementation to Todo. Route/repair real blockers and PRODUCT QUESTION returns. Repair
                        required cancelled coverage/dependency edges. When an In Progress work:product parent's required
                        implementation children are Done and product acceptance can be evaluated, leave concise
                        evidence/context for Product Owner. Do not create activity for unchanged work already waiting on
                        another owner and do not ask PM to seed source main for an assigned first Developer task.
                        """))
                .to("tack:act?id=pm-active-delivery-update&toolProfile=" + ProjectManagementToolProfile.ID)
                .to("tack:thought-exit?name=pm-active-delivery");

        from("direct:pm-work-intake")
                .routeId("pm-work-intake")
                .setProperty("decision.pm-has-product-for-delivery.result", constant("no"))
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        One work:product parent for delivery planning, topology recovery,
                        product-question handling, or coverage/dependency repair.
                        """))
                .to("tack:thought-enter?name=pm-intake&maxTokens=250")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Select at most one item carrying work:product, never work:implementation. Prefer Todo initial PO
                        handoff and validate canonical acceptance. Otherwise select qualifying work:product In Progress
                        parents needing additional decomposition, cancelled-coverage recovery, dependency repair,
                        acceptance-feedback remediation, topology recovery, or reevaluation after clarified product
                        context. An active implementation child/open PR does not make a parent ineligible for further
                        safe planning. If labels are missing from the current projection, read classification rather
                        than guessing from wording. Raise/refresh PRODUCT QUESTION only when product judgment or usable
                        acceptance is genuinely missing. Never invent product scope or implement the parent.
                        """))
                .to("tack:act?id=pm-intake-review&toolProfile=" + ProjectManagementToolProfile.ID)
                .setHeader(TackHeaders.PROMPT, constant("""
                        Is one Todo or qualifying In Progress work:product parent selected with readable usable
                        acceptance and a real decomposition/replanning/topology/coverage-recovery need? Answer no for
                        work:implementation or when a PRODUCT QUESTION remains unresolved.
                        """))
                .to("tack:decide?id=pm-has-product-for-delivery")
                .to("tack:thought-exit?name=pm-intake");

        from("direct:pm-task-planning-loop")
                .routeId("pm-task-planning-loop")
                .setProperty("pmTaskPlanningCycle", constant(0))
                .setProperty("pmTaskPlanningMadeProgress", constant(false))
                .setProperty("pmTaskPlanningPassProgress", constant(true))
                .setProperty("decision.pm-task-planning-done.result", constant("no"))
                .loopDoWhile(simple("${exchangeProperty.pmTaskPlanningPassProgress} == true && ${exchangeProperty.pmTaskPlanningCycle} < 10"))
                    .setProperty("pmTaskPlanningCycle", simple("${exchangeProperty.pmTaskPlanningCycle++}"))
                    .setProperty("pmTaskPlanningPassProgress", constant(false))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Second-stage decomposition: one selected work:product parent, current acceptance, existing
                            work:implementation coverage, dependency-graph viability, assignees, and exactly one
                            missing/remediation implementation outcome this pass. One-child-per-pass
                            is mutation size, not total planning depth.
                            """))
                    .to("tack:thought-enter?name=pm-planning&maxTokens=300")
                    .setHeader(TackHeaders.PROMPT, constant("""
                        Freshly read canonical acceptance for the selected work:product parent and existing
                        work:implementation children. Build a coverage map and reachable dependency graph. Create or
                        refine exactly ONE missing/remediation/replacement/oversized work:implementation child or
                        dependency/topology repair per pass. Ensure the Plane label work:implementation exists and apply
                        it to every implementation child you create. Never apply work:product to implementation
                        children. This one-child limit controls mutation size only; it does NOT mean only one child may
                        exist or that PM should wait for current Developer work to merge before planning more.
                        
                        Every required behavior needs non-duplicative non-cancelled coverage. If a future implementation
                        child depends on unfinished work, CREATE IT NOW in Backlog with the explicit dependency instead
                        of omitting it. If dependencies are already satisfied, make it assigned Todo. Todo + Developer
                        assignee is the authoritative handoff; do not require a Mattermost lease and do not move it In
                        Progress yourself. A Developer may have one active/unmerged source unit while PM maintains
                        several future children. If technical topology genuinely cannot be inferred, create exactly one
                        dependency-free Todo work:implementation TOPOLOGY INVESTIGATION
                        for the exact ambiguity rather than guessing.
                        
                        Each normal work:implementation child must link its work:product parent acceptance behavior, one
                        Developer assignee, priority, repo, dependencies/independence, next step, and expected
                        validation/review evidence. Move initial Todo work:product parent -> In Progress when taking
                        delivery. Do not encode workflow state in comments.
                        """))
                    .to("tack:act?id=pm-actionable-dev-task&toolProfile=" + ProjectManagementToolProfile.ID)
                    .choice().when(exchangeProperty("act.pm-actionable-dev-task.durableProgress").isEqualTo(true))
                        .setProperty("pmTaskPlanningMadeProgress", constant(true)).setProperty("pmTaskPlanningPassProgress", constant(true)).end()
                    .setHeader(TackHeaders.PROMPT, constant("""
                        Is THIS work:product parent sufficiently decomposed for now? Answer yes when its required
                        acceptance has non-cancelled work:implementation coverage, dependency graph is viable,
                        dependency-satisfied children are assigned Todo, dependency-blocked future children are
                        represented in Backlog with explicit edges, current feedback has remediation, no unresolved
                        product question blocks intent, and topology findings are dispositioned. Do not answer yes
                        merely because one child is currently active or has an open PR. Otherwise answer no.
                        """))
                    .to("tack:decide?id=pm-task-planning-done")
                    .choice().when(exchangeProperty("decision.pm-task-planning-done.result").isEqualTo("yes"))
                        .setProperty("pmTaskPlanningPassProgress", constant(false)).end()
                    .to("tack:thought-exit?name=pm-planning")
                .end();

        from("direct:pm-team-sync")
                .routeId("pm-team-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only recent project chat needed for one useful exchange. Do not require an existing
                        conversation before posting. Initiate one concise message when new teammate onboarding,
                        product-to-delivery handoff clarification, real dependency/blocker question, merged-work notice,
                        or other meaningful event would help a current teammate act sooner. Do not repeat a Plane Todo +
                        assignee handoff as a required chat lease. Avoid generic status. Plane remains authoritative for
                        assignments/blockers/state and Gitea for implementation/PR state.
                        """))
                .to("tack:act?id=pm-team-chat-sync&toolProfile=" + TeamChatToolProfile.ID);

        from("direct:pm-documentation-status-sync")
                .routeId("pm-documentation-status-sync")
                .setHeader(TackHeaders.PROMPT, constant("""
                        Read only current mission delivery/navigation anchors when needed. Prefer known ids and
                        direct files. Keep daily work state in Plane.
                        """))
                .to("tack:act?id=pm-workspace-status-read&toolProfile=" + SourceControlToolProfile.ID);
    }
}
