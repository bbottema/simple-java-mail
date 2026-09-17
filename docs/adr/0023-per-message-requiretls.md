# ADR 0023: Make REQUIRETLS an opt-in Email envelope requirement

- Status: Accepted and implemented for unreleased 10.0.0
- Decision date: 2026-09-17
- Applies to: Planned 10.0.0 work in [#741](https://github.com/bbottema/simple-java-mail/issues/741), step 7 of [#722](https://github.com/bbottema/simple-java-mail/issues/722)
- Implementation plan: [Per-message REQUIRETLS](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-4-modern-esmtp/07-add-per-message-requiretls.md)

## Context

Requiring TLS between the application and its configured SMTP server does not require that server to use TLS on later relays. Some messages need that additional requirement while others do not. Automatically attaching it whenever the connection strategy requires TLS would change delivery behavior for existing applications.

[RFC 8689](https://www.rfc-editor.org/rfc/rfc8689.html#section-2) provides a transaction-level REQUIRETLS parameter. Its scope is the message's onward transmission, not a recipient preference or a MIME transformation. This is distinct from the separate `TLS-Required: No` header, which is not part of this feature.

## Decision

Keep first-hop connection security on `Mailer` / `TransportStrategy`. Add `withTlsRequiredForOnwardDelivery()` and `clearTlsRequiredForOnwardDelivery()` to the composed and exact Email builders, with `boolean isTlsRequiredForOnwardDelivery()` on Email. Require explicit opt-in; do not add a recipient setting, transport strategy, policy enum, boolean setter overload, best-effort mode, or feature-specific Mailer setter.

This applies [ADR 0001's ownership rules](0001-email-configuration-scopes-and-inheritance.md) and [ADR 0002's defaults/overrides contract](0002-email-defaults-and-overrides.md). Reuse Email templates and their existing suppression controls. Add `simplejavamail.defaults.requiretls=true` as an ordinary property-backed Email default, including the normal Spring and CLI integration. Do not introduce another policy-resolution mechanism.

The internal model must retain the difference between unset and configured values, even though the public getter returns a boolean. An empty template must not replace a requirement with an implicit false. Clearing removes a local choice and permits fallback; it does not suppress an applicable default or override. A false default does not request plaintext or the `TLS-Required: No` header. Copying and `toBuilder()` preserve the choice.

For exact EML, carry this choice as envelope metadata. Preserve the submitted bytes and the existing bypass of Email defaults/overrides described in [ADR 0013](0013-exact-eml-submission.md). This addition does not authorize applying templates to exact messages.

### Check the send connection, not a prior probe

Before MAIL FROM, require actual TLS, certificate trust and server identity validation, and REQUIRETLS capability on that secured connection. Reject a missing prerequisite rather than silently sending without the requirement. An earlier diagnostic probe inspected a different connection and cannot establish these facts for the send.

An opportunistic SMTP strategy can satisfy these checks if its actual connection is secure; the selected strategy alone is neither proof nor a reason to reject it. Do not mutate a shared Session's settings to implement a per-message choice. Use the provider's supported MAIL extension path, without replacing its transaction state machine.

Characterize STARTTLS, implicit TLS, caller-owned Sessions, custom socket factories, third-party adapters and `CustomMailer` before claiming support. A required real send must not silently succeed through an integration that cannot honor the requirement. The implementation plan must establish that support boundary; this record does not claim those paths already work.

### Report use separately from intent and acceptance

Add `boolean isRequireTlsUsed()` to `MailSubmissionReceipt`. True means the submission actually issued MAIL FROM with REQUIRETLS, including when that command was rejected. Selecting the Email setting is not sufficient. Local pre-command failures and logging-only sends report false. False means no confirmed use was reported, not proof about the behavior of an opaque provider.

Keep this fact per attempt, preserving it through the existing result, exception and observer paths without leaking it through pooled transports or shared connections. [ADR 0011](0011-transport-neutral-submission-outcomes.md) still governs acceptance and uncertainty. This field proves neither downstream compliance nor final delivery and is not an end-to-end content-encryption claim.

## Alternatives and consequences

- Automatically enabling REQUIRETLS with mandatory connection TLS would couple two different requirements and prevent delivery through servers that otherwise satisfy the chosen first-hop strategy.
- A new Mailer feature setter would duplicate the existing Email defaults/overrides mechanism. Recipient setters would imply a distinction this transaction-level parameter does not support.
- Best-effort fallback would weaken the caller's explicit requirement. Unsupported configurations need a concrete failure explaining what must change.
- MIME headers cannot substitute for the REQUIRETLS envelope parameter. Keeping it outside MIME also preserves exact and protected content.

Enterprise-wide enforcement is deliberately deferred to [#740](https://github.com/bbottema/simple-java-mail/issues/740). This ADR adds no enforcement switch, minimum-requirement framework, or change to suppression/override semantics.

## Verification required before delivery

The linked step plan covers provider characterization, wire assertions, configuration presence and precedence, exact-byte preservation, DSN interactions, result accuracy, pooled isolation and all existing send paths. Follow the root [API expansion workflow](../../API_EXPANSION_WORKFLOW.md) and coding guide. The implementation remains unreleased; this accepted decision is not by itself evidence that the feature shipped.
