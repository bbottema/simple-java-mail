# ADR 0022: Generate DSN identifiers per send attempt and keep them outside MIME

- Status: Accepted scope and design; implementation pending review and delivery
- Recorded: 2026-09-16
- Applies to: Unreleased 10.0.0 ENVID work, [#736](https://github.com/bbottema/simple-java-mail/issues/736)
- Implementation: Present as uncommitted work on top of `a4eda9e6` at recording; not a shipped or committed feature

## Context and historical rationale

A later DSN needs an identifier for the submission that caused it. A reusable Email's MIME Message-ID does not distinguish retries or concurrent submissions, and rewriting it can damage exact or protected content. The current [#736 scope](https://github.com/bbottema/simple-java-mail/issues/736) explicitly selects automatic transaction identifiers, optional fixed values and receipt reporting while deferring ORCPT and recipient-specific NOTIFY.

The [provider characterization](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-4-modern-esmtp/06a-dsn-provider-characterization.md) establishes the architectural constraint: Angus 2.0.5 exposes a supported per-message MAIL extension hook for ENVID, but no corresponding per-recipient option API. The hook itself does not check DSN support or encode/validate the identifier. Replacing recipient-command processing would also take responsibility for partial results and replies, so that larger decision was deferred.

The [approved implementation plan](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-4-modern-esmtp/06b-envid-implementation.md) and local code are the implementation evidence. There is no implementing commit to cite yet. The broader [DSN scope ADR](0001-email-configuration-scopes-and-inheritance.md) is not evidence that all recipient-level DSN features are already implemented.

## Decision

For the bundled Angus adapter, generate a fresh UUID on each actual submission when the connected server advertises usable DSN. Generate after connection/TLS/authentication against that transport's current capabilities. Store the generated value in the transport result and submission receipt, never on the reusable Email, shared Session or pooled transport. Reusing or concurrently sending an Email therefore creates independent correlation identifiers.

Allow a fixed unencoded identifier on composed and exact Email builders through `fixingEnvelopeId(...)`; null resumes automatic behavior. Preserve fixed values through copying, `toBuilder()`, partial DSN updates, and the existing whole-value DSN governance. Validate non-empty printable ASCII and the xtext wire-length limit in the immutable model before resource acquisition: at most 94 encoded characters for the value, or 100 including `ENVID=`. Do not trim or replace caller input. The adapter owns wire encoding and the per-message provider facade.

Automatic generation is best effort: without usable DSN, ordinary sending continues without ENVID. A fixed identifier expresses a requirement: reject before MAIL FROM if the selected adapter or actual connection cannot honor it. Existing NOTIFY/RET remain best effort. An older adapter must opt in before handling a fixed identifier; an opaque `CustomMailer` owns its own enforcement and reporting.

Report the effective unencoded identifier through `MailTransportResult` and `MailSubmissionReceipt`, including unsuccessful submissions that used one. An identifier does not establish acceptance or delivery. Preserve the same receipt through observers and failures; null remains the absence value for unsupported paths and older serialized receipts.

Preserve unrelated raw MAIL extensions. A raw ENVID suppresses automatic generation without being reinterpreted or reported as a verified typed identifier; combining raw and fixed ENVID is rejected. Keep transaction metadata in `DeliveryEnvelope` and the provider facade so exact/protected MIME remains unchanged. See [ADR 0007](0007-provider-neutral-mime-boundary.md) and [ADR 0013](0013-exact-eml-submission.md).

## Alternatives and consequences

A globally configured identifier or a UUID generated while building Email would be reused across attempts. Generating at the adapter avoids that state leakage and makes the actual connection authoritative for capability checks. Explicit fixed values deliberately retain caller control, including deliberate reuse.

An explicit-only ENVID API was an earlier local implementation stage; the plan records the later automatic/fixed revision. Document that final direction rather than treating the earlier characterization as the finished design. A generic requirement-policy enum, recipient-command rewriting and a bounce-processing service are outside this approved slice.

No configuration property or Spring metadata default is added for a per-send identifier. Java and Spring applications use Email APIs; generated CLI options expose the composed and exact paths. Existing pool, deadline, observer and shutdown ownership remain unchanged. DSN receiving/correlation stays application-owned. The separately approved [homogeneous configuration slices](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-4-modern-esmtp/06c-homogeneous-email-configuration.md) now implement recipient NOTIFY and automatic ORCPT locally, using the managed command hook and the support boundaries in ADR 0001; they do not redefine #736's narrower delivery scope.

## Implementation evidence and limits

- Local [DeliveryStatusNotification](../../modules/core-module/src/main/java/org/simplejavamail/api/email/config/DeliveryStatusNotification.java) and [MailTransportAdapter](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/spi/MailTransportAdapter.java) define fixed-value validation and provider opt-in.
- Local [AngusMailTransportAdapter](../../modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusMailTransportAdapter.java) checks the connection, creates the per-message facade and reports ENVID without changing protected bytes.
- Local [SmtpEnvelopeIdTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpEnvelopeIdTest.java) and [DSN characterization tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpDsnCharacterizationTest.java) are review evidence. Verification results in the plan belong to the implementation work; no Java tests were rerun for this ADR.
- #736 was open and explicitly awaiting implementation acceptance/delivery when researched. This record preserves that status and does not mark Phase 4 complete.
