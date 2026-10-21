Let's holistically review the 10.0.0 public API for simplicity, consistency and ergonomics. Does it still deserve the name Simple Java Mail? Bring the findings to me before changing anything.

# Public API review and repair workflow

Use the opening prompt with this guide to start a new round of gap analysis. The maintainer owns the design decisions and authorization to execute repairs. The lead agent investigates, presents findings, coordinates approved work and reviews its delivery.

Use the [API expansion workflow](API_EXPANSION_WORKFLOW.md) for implementation requirements, the [maintainer workflow](MAINTAINER_WORKFLOW.md) for GitHub and delivery conventions, and the [ADR index](adr/README.md) for architectural decisions. The approval gates here govern this review round, including when implementation instructions from those guides may begin.

## 1. Start with a read-only review

The opening prompt authorizes investigation and findings. It does not authorize repairs, ADR edits, GitHub mutations or implementation subagents.

Inspect the current branch, working tree, index, linked worktrees and nested website repository. Establish the review revision and target release without switching branches or disturbing ongoing work. Read the relevant ADRs, supported public contracts, implementations, tests, history and usage guidance. Any delegated review stays read-only.

Review the API through realistic caller journeys: first send, reusable configuration, composition and copying, attachments and protection, conversion, execution and shutdown, and result inspection. Look for inconsistent names or types, unnecessary overloads, unclear ownership, hidden defaults, surprising behavior and examples that no longer work. Distinguish supported application APIs, provider SPIs and internal construction machinery.

Present a numbered, prioritized list of concrete findings before changing files. For each finding include:

- A minimal usage example that exposes the gap, with the relevant revision or released baseline.
- The effect on callers and source evidence for the claim.
- The relevant ADR decisions, including any deliberate tradeoff that may explain the current API.
- A proposed direction and the questions the maintainer needs to decide.

Read existing tests as evidence. Defer writing regression tests, running repair/build commands and creating exploratory implementation files until execution is authorized. Keep speculative concerns distinct from confirmed defects. An accepted ADR may resolve an apparent gap without any repair.

## 2. Discuss and approve one finding at a time

Follow the maintainer's chosen order. Before discussing each finding, check its relevant ADRs and earlier decisions again where necessary. Explain the current usage, proposed usage, alternatives and consequences in terms of caller behavior.

Resolve the boundaries that matter to that finding: configuration precedence and locks, unset/clear/reset/disable semantics, collection identity and occurrences, snapshot and resource ownership, lazy versus eager content access, failure and lifecycle behavior, and compatibility. Compare released APIs with the proposed target; do not describe an abandoned unreleased API as a released migration obligation.

Keep this conversation at the design level until the maintainer asks to move on to execution. Record whether the finding is accepted for repair, deferred, rejected or already covered by an existing decision. Do not treat a question, discussion of an option or silence as approval.

| Decision | Required maintainer authorization | What may happen next |
| --- | --- | --- |
| Design approval | Accept the concrete behavior, public usage and scope for this finding. | Record the decision in ADRs, open or update its tracking issue, and prepare the implementation work packet. |
| Execution approval | Authorize implementation and delegation of that agreed work. | Assign a subagent and implement, test and synchronize the affected surfaces within the approved scope. |
| Delivery authorization | Authorize the reviewed work to be committed, pushed to specified branches and closed as applicable. | Deliver the accepted result and complete issue bookkeeping after verification. |

Design agreement alone does not authorize implementation. An instruction such as “Agreed; update the ADR, create the issue and put a subagent on it” can satisfy the design and execution gates together. The maintainer can also authorize delivery in advance. Record the scope of that authorization and do not ask for it again while it still applies. Routine implementation choices inside the approved contract do not need another design decision.

If new evidence requires a different public contract, behavior or scope, bring that choice back to the maintainer before implementing it. Continue independent work that is already approved.

## 3. Record the decision and open the issue

After design approval, reconcile the relevant Architecture Decision Records (ADRs). Clarify an existing ADR when it already owns the decision; create a new record for a distinct architectural choice. Link superseding decisions and update the [ADR index](adr/README.md) when needed.

ADRs contain the architectural context, decision, rationale and consequences. Keep implementation tasks, functional specifications, acceptance checklists and dated test results in the issue, active plan, tests or commit/CI evidence. Record the real decision timing; an accepted ADR does not mean its implementation is finished.

Create one GitHub issue for each meaningful, coherent gap, or reuse an issue that already tracks that exact gap. Group related corrections when they share a contract; avoid generic audit tickets that hide unrelated changes. Minor typos, formatting and incidental wording tweaks do not need separate issues.

Write issue titles, bodies and completion comments in casual, plain English, as a maintainer explaining a change to another developer. Favor short, connected paragraphs with a natural flow. Avoid stacked declarations, disconnected assertions and repeated labels such as "Scope:", "Contract:" and "Boundary:". Use headings, bullets and checklists when they help readers follow distinct points or tasks; the bookkeeping checklist below is not an issue-body template.

The issue must:

1. Start with a short **problem statement** explaining what goes wrong or makes the API awkward and why it matters to the caller. Follow it with the **before usage example** that reproduces or demonstrates the gap, then explain the agreed expected behavior.
2. Link the governing ADRs and identify the intended scope and acceptance criteria.
3. Use the repository's existing canonical `bug`, `enhancement` and/or `documentation` labels as appropriate. Follow the maintainer workflow for additional labels and preserve intentional labels on reused issues.
4. Belong to milestone **10.0.0** and the existing **Simple Java Mail 10.0.0** project board. Resolve their live identifiers rather than creating replacements.
5. Remain **open** while implementation, review or delivery is pending. Use **Todo** before execution and **In Progress** when approved work starts; **Done** follows accepted completion and closure.

Keep the before example in the body. The final after example belongs in the completion comment, so the issue preserves both the original gap and its resolution. A reused issue can retain its earlier context below the concrete gap under review.

Maintain a concise active plan under `docs/plans/` when the round needs coordination. Link the issues and ADRs, record design/execution approval, dependencies, file ownership and remaining verification/documentation obligations. GitHub owns ongoing issue status; the plan explains how the approved work will be completed.

## 4. Obtain execution approval and delegate the agreed work

Before starting an implementation subagent, present a concrete work packet or confirm that the maintainer's existing instruction already approves it. Include:

- The issue, accepted ADRs, before/after usage and acceptance criteria.
- The exact modules, files and responsibilities owned by the subagent, including applicable website work.
- The approved scope and any questions explicitly deferred to the maintainer.
- Required behavior checks and the documentation/migration surfaces to reconcile.
- The delivery authorization already granted, including whether commits, pushes or closure are permitted.

Tell the subagent that it is not alone in the codebase: preserve other work, do not revert others' edits, and accommodate concurrent changes. Give independent workers non-overlapping ownership or explicitly coordinate shared files. The lead agent retains responsibility for the combined result.

Subagents implement and report within their assigned scope. They must return new design questions to the lead agent, which brings them to the maintainer. They must not independently broaden the API, commit unrelated changes, push or close issues without the relevant authorization.

Once an issue's execution is approved, its subagent may work while the lead agent discusses the next finding with the maintainer. Approval is per issue: parallel work does not authorize the next unresolved design.

## 5. Implement and keep every affected surface in sync

Follow the [API expansion workflow](API_EXPANSION_WORKFLOW.md) and [development guide](DEVELOPMENT.md) after execution approval. For behavioral defects, use a focused failing regression followed by the fix and relevant controls. For API changes, check actual supported usage and runtime semantics; a compilation check alone does not prove behavior. Use the appropriate reactor/module checks and generated artifacts rather than stale installed dependencies.

Treat the following as part of the same issue. Inspect each applicable surface and record either its update or why no change is needed:

| Surface | Required alignment |
| --- | --- |
| Public API and Javadocs | Final signatures, names, defaults, ownership, exceptions, getters and clear/reset companions; examples must use the accepted API. |
| Implementations and integrations | Main/optional modules, configuration properties and locks, Spring metadata, serialization, CLI syntax/help and generated CLI caches where affected. |
| ADRs and architecture guidance | The accepted decision, its rationale, related records and topic index; update concurrency/lifecycle guidance when behavior changes. |
| Root documentation | Relevant README examples, contributor/maintenance guidance and unreleased release-history entries. |
| Website documentation | Current feature guides and API examples, navigation and relevant migration guidance. Describe current behavior accurately without implying an unreleased feature has shipped. |
| Journal | Prose, inline/fenced/HTML Java snippets, embedded examples and downloadable source or fixtures under `simplejavamail.org/src/journal/` and associated example assets. Preserve the article's narrative while correcting current recommended usage. |
| Case studies | Scenario code, configuration, resource ownership, explanations and downloadable/runnable examples under `simplejavamail.org/src/case-studies/` and associated assets. |
| OpenRewrite | The [implementation plan](research/10.0.0-openrewrite-migration-implementation-plan.md) and [research/coverage inventory](research/10.0.0-openrewrite-migration-research.md): affected signatures, rule/report classifications, guide anchors, candidate transformations and validation fixtures. |

Search for old symbols and behavior descriptions across all these surfaces, including downloadable Java, shared helpers and example bundles. Read the surrounding usage: changing a method name can still leave an incorrect ownership, activation or defaults example. Compile/type-check applicable examples against the final target and check affected rendered pages and links. Distinguish compiled examples from fragments reviewed in context; do not claim a complete compilation sweep from a text search.

Historical Journal examples may remain when they intentionally illustrate an older approach, but label that context clearly and make the recommended current alternative correct. Do not silently present obsolete code as usable current API.

Keep migration notes limited to changes affecting released supported callers. In the OpenRewrite plan, distinguish safe automatic edits, conditional candidates and report/manual-review cases. Anchor old signatures to the actual released baseline and validation to a frozen target artifact set. Corrections to an unreleased 10.0.0-only API belong in target fixtures and guidance, not invented 9.x migration recipes. Updating the plan does not imply that production recipes have been implemented.

## 6. Review the subagent's result before delivery

The subagent reports its changed files, implemented contract, actual checks and results, documentation/migration updates, limitations and unresolved questions. The lead agent then reviews the actual combined diff and evidence against the issue, approved design and ADRs. A subagent's completion message alone is not acceptance.

Check that the repair closes the reported gap without changing an unapproved contract, and that meaningful boundary cases have coverage. Review the relevant configuration/lock, copying/serialization, ownership, lazy-content, execution and failure paths. Apply the checks that fit the change and complete required verification; avoid repeatedly rerunning already sufficient checks without new evidence.

Review the documentation matrix as well as production code. Confirm that issue examples, website docs, Journal and case-study snippets use the final API, and that the OpenRewrite plan agrees with the implementation and released compatibility boundary. Use an independent reviewer when helpful, while retaining lead-agent accountability.

Return implementation defects within the approved design to the subagent. Return design changes or material scope expansion to the maintainer. Keep the issue open while required work or verification remains.

Present the maintainer with a concise outcome for each issue: the repaired usage, important behavior/compatibility consequences, review findings, verification evidence and remaining limitations. Make the concrete diff and relevant examples available. Proceed with delivery only within the maintainer's authorization; if it is missing, request it for this reviewed result. Honor delivery permission already granted.

## 7. Commit, deliver and close the issue

Follow the [commit and push conventions](MAINTAINER_WORKFLOW.md#8-commit-and-push). Inspect root, nested website and staged/unstaged state again. Commit only the approved work, in selective semantic commits that remain understandable per issue. Preserve unrelated work and keep the website gitlink consistent with the reviewed website commit.

Every issue-associated commit subject ends with its issue numbers in parentheses:

```text
fix(email): preserve copied notification choices (#123)
feat(api): align related configuration contracts (#123, #456)
```

Name the governing ADRs and explain the alignment in the body, for example `Architecture: ADR 0002, ADR 0031.` Apply the same convention to supporting documentation and website commits. Website bodies also carry qualified main-repository issue references. Do not add sign-offs. Body references alone do not replace the subject suffix.

Push only to the branches authorized for the round. When publishing both repositories, publish the website commit before the parent gitlink and verify the remote heads. History rewriting, force-pushing, deployment and releasing require their own explicit authorization; ordinary implementation approval does not imply them. If publication is still pending, retain the reviewed local commits and keep the issue open.

Once the accepted implementation and its required companions are delivered:

1. Add a completion comment starting with the **after usage example** using the final supported API.
2. Briefly explain the resolved behavior and link the relevant delivered commits and ADRs. Preserve the original before example in the issue body. Distinguish completion on the 10.0.0 development branch from a published release.
3. Close the issue as completed and set its project item to **Done**. Do not close unrelated or unfinished issues merely because a shared commit mentions them.
4. Verify the issue's labels, milestone, board status and commit references, including any references affected by an explicitly approved history rewrite.

If review rejects or defers a proposed repair, record the maintainer's disposition without marking unimplemented work as completed.

## 8. Finish the round and clean up

Report completed issues and commits, deferred findings, pending approvals and any remaining compatibility or verification gaps. Reconcile the final public API with the ADRs, website, Journal, case studies and OpenRewrite plan once more across issues that interacted during the round.

Keep durable decisions in ADRs, ongoing status in GitHub, regression coverage in tests and dated execution evidence in commits/CI. Retire completed plans and one-off audit/verification reports after preserving their useful material in the maintained guides.

Use a bounded, task-owned scratch directory for temporary probes and track what was created. Stop owned helper processes and remove those temporary files when finished. Preserve other ongoing tracks, registered worktrees and unrelated artifacts; never clean a shared `.local` or temporary parent directory wholesale.
