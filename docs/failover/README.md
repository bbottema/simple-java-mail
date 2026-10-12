# SMTP connection failover

This folder contains active implementation documentation for coordinated SMTP connection failover in the unreleased 10.0.0 development line. It explains the current architecture and the contracts to preserve as the code evolves.

Start with the [architecture guide](architecture.md) for the onion responsibility map, Mermaid UML, abstraction lifetimes, destination ownership, routing, recovery and transport cleanup. Use the concurrency page for monitor boundaries and interleavings; the ADR records the design rationale.

| Question | Documentation |
| --- | --- |
| Where does failover fit, and which abstractions own it? | [Implementation architecture](architecture.md) |
| Which seams deserve deeper review in the current implementation? | [Prioritized ownership and handoff seams](architecture.md#11-deeper-implementation-review-seams) |
| What did the latest code review find and verify? | [12 October ownership seam review](code-review-2026-10-12.md) |
| Who may recheck a failed destination, and which observations remain valid? | [Connection failover and recovery state machines](../concurrency/10-connection-failover.md) |
| Which APIs and ownership rules were chosen? | [ADR 0029](../adr/0029-smtp-submission-failover.md) |
| How do I configure Java, properties, Spring or CLI failover? | [User guide source](../../simplejavamail.org/src/pages/smtp-connection-failover.hbs) |
| Where are the feature and related work tracked? | [#755](https://github.com/bbottema/simple-java-mail/issues/755), the clustered-ownership correction [#757](https://github.com/bbottema/simple-java-mail/issues/757), and the [published pool integration record](../research/755-failover-state-refactor-verification.md#authorized-supporting-library-cascade-12-october-2026) |
| Which compatibility and conformance checks have been recorded? | [Implementation verification](../research/755-connection-failover-verification.md) |
| What protected the recovery-state refactor before it started? | [Public-boundary regression baseline and review gate](../research/755-failover-blackbox-baseline.md) |
| What changed in the explicit recovery-state refactor, and how was it verified? | [State-refactor verification](../research/755-failover-state-refactor-verification.md) |
| What options and evidence informed the design? | [Original research](../research/755-smtp-submission-failover.md) |
| How do I maintain or optimize the shared onion image? | [Infographic source and publication paths](../concurrency/inside-a-mail-send.md) |

The central contract is connection-only routing before submission. The initiating Mailer owns execution, deadline, cancellation and observation; the selected Mailer owns destination-specific preparation and connection settings. Physical setup evidence may permit another destination under policy. Busy pools, sending-limit waits, local preparation errors and provider invocation do not authorize replay.

When changing routing, preparation, recovery or cleanup:

1. Update the architecture guide and the affected concurrency contracts together with the implementation.
2. Run the relevant regressions listed in the [architecture guide](architecture.md#10-regression-evidence-and-maintenance-verification), including wire tests when provider setup evidence changes.
3. Reconcile and render the Mermaid diagrams, then review the [shared infographic wording and artwork](../concurrency/inside-a-mail-send.md). Record the maintenance checkpoint there.
4. Verify compatible pool dependency versions and the dated verification record before release. Keeping current architecture documentation does not advance the implementation plan or establish release publication.
