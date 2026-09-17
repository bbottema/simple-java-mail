# ADR 0014: Rehearse governed send preparation without SMTP

- Status: Accepted; retrospective record of implemented behavior
- Decision recorded: 2026-09-16; not the original introduction date
- Applies to: Unreleased 10.0.0 validation and rehearsal APIs
- Implementation baseline: `codex/10.0.0` at `a4eda9e6`
- Related decisions: [ADR 0002: Governance](0002-email-defaults-and-overrides.md), [ADR 0013: Exact EML](0013-exact-eml-submission.md)

## Context

An Email draft can omit a From address that the chosen Mailer supplies through defaults. Validating only the draft can therefore reject a message that would send successfully. Conversely, address-only validation can accept a message whose MIME conversion, requested security processing or final encoded size will fail.

Applications also need to inspect, preview or archive what this Mailer would prepare without opening SMTP. Returning only success/failure discards useful prepared facts and forces callers to reproduce governance or conversion themselves.

## Decision

Make `Mailer.rehearse(email)` a no-SMTP execution of the Mailer's preparation rules and return an immutable `MailRehearsal` snapshot. For composed Email, apply this Mailer's defaults/overrides, validate the effective Email, run the normal security/MIME pipeline, serialize once and check the size of those encoded bytes. Return the effective Email, defensive EML bytes, encoded size, effective Message-ID and explicit transport-envelope facts.

Detach the governed Email from the caller's draft before generated Message-ID propagation. Rehearsal may backfill the ID into its own effective Email, but must not change the caller's Email. The rendered EML is the authoritative output of this rehearsal; do not expose a mutable Jakarta Mail MimeMessage as the snapshot API.

`Mailer.validate(...)` delegates to rehearsal and discards the snapshot. The choice between the methods is whether the caller needs prepared output, not a difference in default validation depth. Calling both is redundant. Direct `EmailValidator` use remains a lower-level validation choice with a different scope.

Provide `rehearse(email, false)` and the matching validation overload for a deliberate base-MIME mode. It still applies governance and client checks, but skips security processing and final-size enforcement. Its bytes and size describe the base representation, not the secured message that a later send would produce. Full mode loads the requested optional DKIM, S/MIME and OpenPGP modules and exposes missing-module/security failures just as preparation should.

Share preparation rules with sending without calling public rehearsal from the send path. Sending validates the governed Email and converts it once when using the sending Session. Calling full rehearsal first inside every send would render/sign twice, perform unnecessary work and potentially produce two different generated representations.

## Recorded rationale and evidence

- [#451](https://github.com/bbottema/simple-java-mail/issues/451), 2023, moved defaults and overrides to Mailer governance to support different server policies and avoid repeated expensive preparation at ordinary Email construction. This explains why validation of the raw draft is insufficient; it is not a historical claim that rehearsal already existed then.
- [#688](https://github.com/bbottema/simple-java-mail/issues/688) explicitly contrasts direct draft validation with the actual send path and gives the missing-From/default-From example. It asks whether to change `validate` or add a separate method. [Commit `c7e85c88`](https://github.com/bbottema/simple-java-mail/commit/c7e85c88aa92d358644478d6dc572caa7ca76596), 2026-08-24, chooses governed MIME rehearsal plus the security/size switch. The [implementation comment](https://github.com/bbottema/simple-java-mail/issues/688#issuecomment-5396901377) confirms no SMTP and an unchanged supplied Email.
- [#709](https://github.com/bbottema/simple-java-mail/issues/709) records the remaining problem: validation performs useful preparation and discards it. It explicitly calls for defensive bytes, immutable facts, no caller mutation and a snapshot rather than a promise of identical future output.
- [Commit `1b116cf3`](https://github.com/bbottema/simple-java-mail/commit/1b116cf3ff4febd58c7974ece66dde4454cac80b), 2026-08-26, returns the rehearsal result and routes validation through the same preparation. The [completion comment](https://github.com/bbottema/simple-java-mail/issues/709#issuecomment-5423637347) clarifies validate-versus-rehearse and the base-MIME switch.

The detachment and single-conversion behavior are directly verified in [MailerImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java) and [SessionBasedEmailToMimeMessageConverter](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/SessionBasedEmailToMimeMessageConverter.java). The implementation avoids redundant rendering; interpreting that separation as prevention of duplicate signing/conversion is code-derived architectural reasoning, not an invented quotation from an earlier design meeting.

## Alternatives and consequences

The following comparisons are retrospective analysis, except for alternatives posed explicitly in #688/#709.

- **Keep Mailer validation limited to the raw Email:** preserves the old behavior but continues false rejection with Mailer defaults and misses MIME/security/size failures. Low-level validation remains available independently.
- **Add another full-preflight method while leaving `validate` unchanged:** reduces semantic migration but adds competing validation contracts to an already broad API. The chosen 10.0.0 behavior makes Mailer validation mean preparation through that Mailer.
- **Return a mutable MimeMessage:** exposes provider and mutation behavior through a preview API. Defensive bytes are a stable artifact with an unambiguous size and preserve provider independence of the result.
- **Validate twice or reuse a hidden cached preparation automatically:** repeated preparation costs work; hidden caching introduces lifetime, stale-input and ownership questions. Callers request a snapshot explicitly, while sending prepares normally.
- **Turn off all MIME work in the fast mode:** would no longer expose a usable base representation or its envelope facts. The selected switch skips security and final-size enforcement while retaining base MIME rendering.

Full validation becomes more expensive and can fail for missing requested security modules or oversized encoded content even when address validation succeeds. That is the intended alignment with send preparation. The base mode is useful when those costs are unwanted, but its successful result does not establish that full secured preparation will succeed.

## Implementation limits and current boundaries

Rehearsal does not acquire a Transport or pool lease, start a proxy, call a CustomMailer, produce a send observer outcome, or run normal Session/Email logging. It does not test credentials, connectivity, server capabilities or delivery. The no-network boundary here means no mail transport activity; configured content sources and application-provided data sources still have their own I/O behavior during MIME construction. Logging-only/custom-mailer configuration does not replace the normal preparation described by the snapshot.

For composed messages, a later send prepares again: dates, boundaries, Message-IDs and cryptographic output can differ. A rehearsal through this Mailer is not a frozen reservation of a pooled Session or future server transaction. The snapshot's envelope sender is the explicit override, or absent when a provider will derive its default. Do not claim it has predicted every provider choice.

For exact Email, governance and security processing are intentionally bypassed. Both rehearsal modes expose the unchanged authoritative bytes; full mode adds the raw-size check. The normal exact source has no generated Message-ID to propagate. This exception follows source authority in ADR 0013 rather than weakening composed-message preparation.

The snapshot copies its byte array and recipient list, and its effective Email is the detached prepared view described by [MailRehearsal](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailRehearsal.java). It is not a promise to deep-copy arbitrary attachment/data-source resources or to make a later send byte-identical. Callers requiring an authoritative replay artifact can deliberately use exact EML construction with explicit envelope choices.

[MailerRehearsalTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailerRehearsalTest.java) is the existing regression landmark. This retrospective documentation pass did not rerun Java tests or claim new runtime verification.
