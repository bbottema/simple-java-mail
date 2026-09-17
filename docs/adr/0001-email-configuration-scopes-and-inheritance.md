# ADR 0001: Email configuration scopes and inheritance

- Status: Accepted architectural direction; existing behavior recorded separately below
- Decision date: 2026-09-16
- Applies to: The 10.0.0 API and subsequent API additions
- Implementation status: DKIM consolidation, recipient NOTIFY and automatic ORCPT are complete and accepted on 17 September 2026 for 10.0.0; verification and review are recorded in the linked implementation slices. Not yet released.
- Related work: [SMTP robustness plan](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/README.md), [DSN envelope model](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-4-modern-esmtp/06-complete-dsn-envelope-model.md), [#736](https://github.com/bbottema/simple-java-mail/issues/736)
- Security API consolidation: [#737](https://github.com/bbottema/simple-java-mail/issues/737) tracks the shared DKIM/S/MIME configuration contract and DKIM migration, separately from ENVID and recipient NOTIFY/ORCPT.
- Recipient DSN follow-up: [#738](https://github.com/bbottema/simple-java-mail/issues/738) tracks recipient/group NOTIFY preferences and automatic ORCPT, separately from #736 and #737.

## Decision

Email is the canonical home for message configuration. A Mailer supplies reusable Email defaults and overrides through the existing general-purpose APIs, not through a second set of feature-specific setters and backing fields. Recipient and recipient-group builders expose only the settings that have recipient-specific meaning.

Use the same conventions for defaults, explicit values, clearing, and forced overrides wherever those operations are supported. Consistency does not mean placing every field on every builder.

Closed Java choices use typed values, not protocol strings. Recipient/group notification methods accept `DeliveryStatusNotification.NotifyOption` values only; composed and exact Email builders offer the same typed notification and return choices. Existing Email text-input overloads remain for configuration/CLI compatibility, but Java documentation leads with enums and explains their delivery-notification meaning.

For the three feature families discussed here, S/MIME and DSN have recipient-specific settings; DKIM has none. Within those families, individual fields still have different scopes.

## Context and motivation

An application should be able to configure an Email feature once for a Mailer or vary it per message using the same Email API. [ADR 0002: Email defaults and overrides](0002-email-defaults-and-overrides.md) records the reusable-policy mechanism, its historical motivation, and its resolution rules. This record covers the separate question of which message, transaction, or recipient scope a feature belongs to.

The DSN discussion exposed the same design question again: should every feature grow its own Mailer-level defaults API, and should all of its settings then be copied onto recipients? Both approaches make the API harder to predict. They also create multiple places to implement precedence, property defaults, clear operations, and documentation.

The existing recipient builders provide a useful precedent. An S/MIME certificate can be supplied for one recipient or defaulted/fixed across a group. The group is an application-side construction convenience; its members become a flat list of recipients. It is not an RFC address group, a server-side mailing list, or another SMTP transaction.

The aim is practical: a caller should know where a setting belongs and how to reuse it without learning a separate defaulting mechanism for each feature.

## Configuration ownership

### Mailer

Use `withEmailDefaults(...)` and `withEmailOverrides(...)` for centralized message policy. Property-backed message defaults feed the same Email governance path from the immutable configuration snapshot.

Do not add a second Mailer configuration route merely to repeat an Email feature. The dedicated DKIM default-signing migration belongs to [ADR 0002](0002-email-defaults-and-overrides.md#existing-behavior-and-follow-up-work).

Mailer-owned settings remain on Mailer: SMTP endpoints, authentication, TLS, proxies, pools, executors, timeouts, and observer registration. This decision does not move those into Email.

### Email, recipient group, and recipient

The following table records the accepted scopes, now represented by the local implementation. Release status is separate from this architectural decision.

| Setting | Email | Recipient group / recipient |
| --- | --- | --- |
| S/MIME signing credentials and signing algorithm | Message-wide signing configuration | Not exposed |
| S/MIME content-encryption and key-encapsulation algorithms | Shared encryption settings | Not exposed by this design |
| S/MIME encryption certificate | Fallback certificate | Group defaults/fixed certificates and individual certificates |
| DKIM signing domain, selector, key, and signing options | Message-wide signing configuration | Not exposed |
| DSN NOTIFY preferences | Default preferences for the email's recipients | Group defaults/fixed/clear policies and individual preferences |
| DSN RET choice | One choice for the SMTP transaction | Not exposed |
| Fixed ENVID | Caller-selected transaction identifier | Not exposed |
| Automatic ENVID | Generated per supported send attempt; reported on the result | Not recipient configuration |
| ORCPT | No separate user-configured field for ordinary submission | Derived from each actual envelope recipient when supported |

S/MIME signing protects shared message content; our encryption path also produces one message, using the selected recipient certificates. Recipient-specific signing or different content-encryption algorithms would require a different sending model. This decision does not introduce message splitting or recipient-specific message variants. See [S/MIME message construction](https://datatracker.ietf.org/doc/html/rfc8551#section-3).

DKIM's signing domain identifies a party taking responsibility for a message, not its recipients. Email-level configuration is useful when messages are signed for different organizations; a common signing configuration belongs in Email defaults. See [RFC 6376](https://datatracker.ietf.org/doc/html/rfc6376#section-1).

NOTIFY and ORCPT are recipient parameters; RET and ENVID belong to the transaction. Do not put the entire existing `DeliveryStatusNotification` object on Recipient just to share a type. See [RFC 3461 parameter scopes](https://datatracker.ietf.org/doc/html/rfc3461#section-4).

## Recipient fallback and override boundaries

### Defaults and explicit values

For recipient-capable settings, normal fallback runs from the most specific value to the least specific:

```text
recipient value -> recipient-group default -> email-wide value -> Mailer Email default
```

This describes precedence, not a required runtime object hierarchy. Group defaults can be resolved while building the flat recipient list, as they are for S/MIME certificates today. Do not introduce a generic configuration tree merely to express this relationship.

- A default for one recipient setting fills an absent value; it does not overwrite an explicit value. This does not change the separate collection-merging rules in [ADR 0002](0002-email-defaults-and-overrides.md#resolution-contract).
- Preserve recipient settings through copying, group construction, governance, and transport preparation. Duplicate envelope recipients must not lose their individual metadata through address-keyed deduplication.
- Resolve each policy at its declared value boundary. For example, an explicit NOTIFY preference is a complete preference set, not an invitation to merge in events from less-specific defaults.
- Keep source objects immutable. Resolving a fallback for one send must not mutate a reusable Email, group, recipient, configuration snapshot, or caller-owned Session.

The shared distinction between clearing, disabling, and overriding is defined in [ADR 0002](0002-email-defaults-and-overrides.md#clear-disable-and-override-are-different-operations). For recipient settings, DSN `NEVER` is an explicit preference, while clearing a certificate restores fallback. Existing `withFixedSmimeCertificate(...)` replaces the certificates on the group's produced recipients; it is not a default.

Cross-scope precedence is settled: preserve the documented S/MIME behavior and apply the same rule to new recipient settings. An Email override replaces the Email-level value, not an explicit value on each recipient. Group-fixed values replace values on the recipients produced by that group. An explicit recipient value therefore still wins over the governance-resolved Email fallback, including a fallback supplied through Mailer Email overrides.

Implementation tests must pin down those conflicting fixed/override cases and compound-value resolution. This ADR does not introduce a global force operation that rewrites every recipient, a new generic configuration interface, or recipient-specific message variants.

## Automatic metadata is not notification policy

ENVID and ORCPT support correlation. They do not decide which notifications to request, receive notifications, guarantee their arrival, or turn SMTP acceptance into final delivery.

- Keep automatic ENVID generation per supported attempt, independently of an explicit NOTIFY choice. A caller-fixed ENVID remains a caller choice; generated values belong on the receipt, not back on the reusable Email. Do not introduce a global property default for a per-attempt generated identifier.
- The target for ordinary submissions is automatic ORCPT derived from the actual envelope recipient, not the MIME `To` header. Initial-submission ORCPT must match that recipient. Explicit forwarding/gateway address overrides are outside this decision. See [RFC 3461 section 4.2](https://datatracker.ietf.org/doc/html/rfc3461#section-4.2).
- Send DSN parameters only when negotiated support permits them. Server DSN capability and application NOTIFY preferences are separate concerns; automatic ORCPT must not change those preferences.
- ORCPT implementation can proceed independently of configurable per-recipient NOTIFY. Neither becomes complete merely because the other is implemented.

The [provider characterization](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-4-modern-esmtp/06a-dsn-provider-characterization.md) remains relevant: Angus has no dedicated per-recipient parameter setter. The local implementation augments RCPT parameters through the existing managed command hook without replacing Angus's recipient loop, replies or partial-result handling. An immutable ordered `DeliveryRecipient` list carries recipient policy across the provider boundary; duplicate occurrences are never keyed or deduplicated by address.

Explicit recipient preferences require both managed-provider support and usable DSN on the actual connection after TLS/authentication. Reject unsupported requests before MAIL FROM; use the SPI's `MailTransportCompatibilityException` to keep healthy pooled transports reusable. Shared Email NOTIFY/RET remain best effort. Ordinary Angus transports obtained from caller-owned Sessions or custom socket factories keep that older shared behavior and ENVID, but lack automatic ORCPT and cannot accept recipient preferences. Third-party adapters must opt in; `CustomMailer` owns its mapping and rejection policy.

ORCPT uses ASCII xtext, or RFC 6533's UTF-8 address type when Angus enables UTF-8 commands and the connection advertises SMTPUTF8. Optional metadata is omitted for otherwise unrepresentable addresses or a parameter exceeding 500 encoded octets; addresses are never truncated. No forwarding/gateway ORCPT override is exposed. Recipient choices remain Java builder data; existing property/Spring/CLI options describe the shared Email fallback, not an address-specific policy language.

## Existing behavior and follow-up work

The baseline for this decision is `codex/10.0.0` at `a4eda9e6`, including the uncommitted ENVID work present during this discussion. These are implementation facts, not additional target APIs:

| Area | Baseline | Required follow-up |
| --- | --- | --- |
| S/MIME group certificates | Default, fixed, and clear operations resolve into individual recipient certificates. | Keep the useful construction pattern and apply the agreed semantics consistently to new recipient settings. |
| S/MIME Email overrides | A recipient certificate wins over the governance-resolved Email fallback, even if that fallback came from Mailer Email overrides. | Preserve this documented precedence and use the same scope rule for new recipient settings. |
| DSN configuration | One Email-level `DeliveryStatusNotification` value supplies shared NOTIFY/RET and an optional fixed ENVID. | Add only the recipient-capable policy surface; do not duplicate transaction fields onto Recipient. |
| ENVID | The narrower #736 implementation is available for review. | Keep its review and delivery status separate from this wider API decision. |
| ORCPT and per-recipient NOTIFY | Unimplemented at the recorded baseline. | Implemented locally in slices 2 and 3; verify and review separately from narrower #736 ENVID work. |

Relevant code: [Mailer builder](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java), [Email builder](../../modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java), [recipient builder](../../modules/core-module/src/main/java/org/simplejavamail/api/email/IRecipientBuilder.java), [group builder](../../modules/core-module/src/main/java/org/simplejavamail/api/email/IRecipientsBuilder.java), [Email governance](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/EmailGovernanceImpl.java), and [S/MIME resolution](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java).

## Alternatives considered

### Every setting on every level

Rejected. A per-recipient RET, ENVID, DKIM configuration, or S/MIME signing choice would imply behavior the shared-message/shared-transaction path cannot independently honor. Supporting message splitting would be a separate feature and decision, not an invisible consequence of a setter.

### A universal configuration interface or resolver framework

Not required by this decision. Keep cohesive, typed configuration values and readable resolution paths. Reuse implementation where responsibilities genuinely match, without forcing unrelated cryptographic and SMTP settings into one abstraction.

### Keep DSN email-wide forever

Rejected as the target. Different recipients can legitimately need different notification preferences. The existing recipient/group model is the appropriate place to express them once the provider path is implemented.

## Consequences

- Users get one place to configure a message feature and one way to establish Mailer-wide policy for it.
- Recipient builders remain useful for recipient metadata without becoming copies of the Email builder.
- The shared-policy cleanup in ADR 0002 and the recipient-level additions here are separate implementation slices. Neither should obscure the other's migration and review requirements.
- Consistent naming alone is insufficient. Precedence, copying, clearing, explicit disabling, and overrides need behavior tests and public Javadocs.
- Existing ambiguous wording may need correction. Do not rewrite historical version documentation to suggest these target APIs existed there.
- There is no new pool, lock, executor, or send lifecycle in this decision. Exact EML and protected MIME bytes keep their existing boundaries; envelope options must not trigger content rewriting or silently enable governance for exact submissions.

## Implementation and review gate

Follow [API_EXPANSION_WORKFLOW.md](../../API_EXPANSION_WORKFLOW.md) and [CODING_STYLE_GUIDE.md](../../CODING_STYLE_GUIDE.md). Before implementing an affected setting, record:

1. Its owner, supported configuration levels, and whether it is configured input, derived metadata, or a runtime result.
2. Its fallback, clear, disable, and forced-override behavior, including cross-scope conflicts and existing suppression controls.
3. Its migration from existing APIs and property defaults, including the DKIM default/clear behavior that must remain expressible without a separate Mailer signing API.
4. Its propagation through copies, groups, Email governance, explicit envelope recipients, duplicate recipients, serialization, and provider conversion where applicable.
5. Its property/Spring/CLI representation, or why it has no sensible representation there. Do not add global properties for recipient identity or attempt-generated values.
6. Its verification at the real boundary: MIME/signature processing for security configuration; SMTP commands and outcomes for envelope policy; pooled and concurrent isolation where per-attempt metadata is involved.
7. Its compatibility with exact EML, custom mailers, caller-owned Sessions, and providers that lack the relevant extension hook.

Tests must distinguish default inheritance from fixed overrides, and absence from explicit disabling. Documentation must distinguish accepted target design from implemented behavior. Any proposal to duplicate an Email feature on Mailer, or to add an invalid recipient-level setting, requires an explicit superseding ADR rather than a convenience exception.

This record authorizes the architectural direction, not automatic execution of every follow-up. It does not change code, expand #736's acceptance criteria, commit or publish changes, or claim the broader DSN work is finished.
