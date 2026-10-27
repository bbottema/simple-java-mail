# ADR 0002: Email defaults and overrides

- Status: Accepted architectural direction; existing behavior recorded separately below
- Decision recorded: 2026-09-16; this is not the introduction date of the existing mechanism
- Applies to: The 10.0.0 API and subsequent API additions
- Implementation status: Existing mechanism; DKIM consolidation and the related recipient-scope work are complete and accepted on 17 September 2026 for 10.0.0, not yet released
- Related decision: [ADR 0001: Email configuration scopes and inheritance](0001-email-configuration-scopes-and-inheritance.md)
- Implementation tracking: [#737 — Unify DKIM and S/MIME configuration through Email defaults and overrides](https://github.com/bbottema/simple-java-mail/issues/737)

The accepted 21 October 2026 [ADR 0037](0037-repeatable-email-completion.md) supersedes unconditional additive collection concatenation:
repeated completion preserves supplied occurrences and adds only missing template values, while retaining scalar precedence, suppression
and mandatory locks. Its implementation is tracked separately in [#768](https://github.com/bbottema/simple-java-mail/issues/768).

## Decision

Use Email itself to describe reusable message defaults and overrides. Configure these templates through `withEmailDefaults(...)` and `withEmailOverrides(...)`, and apply them through the selected Mailer's Email governance during preparation.

Do not mirror every Email feature with a second set of Mailer configuration methods and fields. A new message feature must define its participation in the common governance mechanism, including its value boundary and suppression behavior. Mailer transport and execution settings remain outside these templates.

ADR 0001 determines which settings belong to Email, a transaction, or a recipient. This record determines how reusable Email policy is represented and applied; it does not turn Email governance into a recursive recipient-configuration framework.

## Why this exists

Applications often reuse a sender address, signing configuration, headers, or attachments across many messages. Repeating them on every message is noisy and easy to get wrong. Putting a dedicated defaulting API for each feature on Mailer solves that immediate inconvenience but duplicates the builder surface, stored state, property handling, clear operations, and documentation.

An Email template already has the vocabulary needed to express those choices. It can be incomplete because it describes policy, not a separate message to send. Reusing it lets callers learn one feature API whether they configure one message or a whole Mailer.

The historical rationale is explicit: commit [c1b266d4](https://github.com/bbottema/simple-java-mail/commit/c1b266d47146d45b51896aeff5c194771aa2d319), dated 26 February 2023, removed `signByDefaultWithSmime(...)` because signing on the defaults Email already worked. S/MIME signing was therefore not exempt from this design. Commit [0eb14398](https://github.com/bbottema/simple-java-mail/commit/0eb143983db804d91c55289a130df3a2bf747f1e), dated 4 July 2026, later added `withDefaultDkimSigning(...)` for #196. That added another configuration route; it does not establish an exception for future features.

Preparation is deliberately later than ordinary `buildEmail()`. The same application-created Email may be sent through Mailers with different sender or signing defaults. Eagerly applying one Mailer's policy would bake the wrong choice into that Email and repeat work while building messages. The [Email builder contract](../../modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java) already explains this timing, including the clustered-Mailer use case.

## Representation and application

1. A configured factory supplies an immutable configuration snapshot. Governance uses that captured input, not a new global property lookup during each send.
2. The Mailer holds reusable defaults and overrides as Email templates. Property-backed message defaults enter this same mechanism; they are not an independent runtime overlay after governance.
3. Preparation resolves the submitted Email against those templates into another Email. It does not mutate the templates or the caller's configured fields to apply the policy.
4. Validation, MIME construction, and security processing use the resolved configuration. Explicit completion helpers remain available when a caller needs that resolved view without sending.

This is a configuration-resolution contract, not a new promise about every runtime field: existing Message-ID propagation back to the submitted Email is separate from applying defaults and overrides.

### Completion resolves outgoing data (clarified 21 October 2026)

Completion carries outgoing content and configuration. S/MIME, OpenPGP and DKIM protection settings and outgoing
`DeliveryStatusNotification` requests retain their ordinary value resolution, suppression and lock rules. Completing an Email does not
perform signing, encryption, verification or decryption; protection is applied during later MIME preparation.

Historical input facts are a separate concern: original S/MIME/OpenPGP results and protected bytes, received `DeliveryStatusReport` facts,
decrypted-attachment inspection lists, retained S/MIME signed-content views and their merge bookkeeping do not belong in a completed
outgoing Email. They are neither template defaults nor send instructions. Ordinary copying and supported serialization preserve input
history; completion creates an outgoing view. Fresh plain/unsigned result metadata does not attest to the source's protection or validate
the output. Inspect the original converted Email for that history.

This distinction does not strip imported message content. Body, headers, ordinary attachments/images, explicit Message-ID/sent-date,
envelope and recipient choices remain eligible outgoing values, including content already flattened from a signed-message view.
[ADR 0008](0008-minimal-mime-structures-and-protection-order.md) owns input protection and interpretation;
[ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md) owns copying and snapshot preservation.
[#819](https://github.com/bbottema/simple-java-mail/issues/819) tracks this clarification and the narrow removal of the earlier received-report
carryover from completion. Outgoing delivery-notification requests and ordinary copy/serialization preservation remain separate contracts.

The current constructor uses either the supplied defaults Email or the snapshot-derived defaults Email. It does **not** automatically fill a supplied defaults template's missing fields from the property snapshot. Repeated `withEmailDefaults(...)` calls replace the reference rather than accumulating templates. Any change to that behavior needs its own reviewed migration.

Name the choice explicitly: `useConfiguredEmailDefaults()` restores the captured property-derived template, replacing the former
`clearEmailDefaults()` name; `withoutEmailDefaults()` selects an empty ordinary defaults template. Neither operation disables configuration
locks. `withEmailDefaults(...)` continues to replace the entire template.

## Resolution contract

Resolution depends on the shape of the value, subject to the submitted Email's suppression settings. Additive collection identity,
ordering and repeated-completion boundaries are detailed in [ADR 0037](0037-repeatable-email-completion.md).

| Value shape | Resolution | Why keep the distinction explicit? |
| --- | --- | --- |
| Single value, such as subject or signing configuration | First non-null value from override, submitted Email, then default. | An explicit value replaces the fallback rather than being combined with it. |
| Collections, such as attachments and TO/CC/BCC recipients | Add missing override entries, retain every submitted occurrence, then add missing default entries, under ADR 0037. | Reusable additions retain caller content without duplicating already present template contributions. An "override" template does not replace the whole collection. |
| Headers | Combine keys; for the same key, replace the entire value collection in default, submitted, then override order. | Different header keys can coexist, but conflicting values for one key have a clear winner. |
| Compound configuration values | Resolve at the declared value boundary, not recursively by every member field. | Combining parts of separate signing or notification policies can create a configuration neither caller requested. |

Header identity is case-insensitive. Within an already materialized Email that contains multiple spellings of one name, retain the last
matching entry in that source's iteration order, as the previous map-based resolution did. The original outer/inner ownership is no longer
recoverable from that flat map. New S/MIME merges resolve the collision before flattening, with inner-header precedence under
[ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md#smime-header-identity-clarified-26-october-2026).
Template suppression and default/submitted/override precedence still apply after this interpretation. This clarification is tracked in [#867](https://github.com/bbottema/simple-java-mail/issues/867).

Under the accepted [ADR 0039 logical-identity extension](0039-explicit-configuration-resolution.md#logical-setting-identity-and-complete-mailer-preparation-accepted-26-october-2026),
capture preserves header names, origins and eligibility as keyed declarations until selection is complete. Subject/body and other scalar
declarations also identify their logical target, so sharing a Java value type cannot accidentally combine unrelated fields. This changes no
template precedence, completion timing, public setter, suppression or exact-message contract.

These rules are implemented by [EmailGovernanceImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/EmailGovernanceImpl.java), the API [EmailProperty](../../modules/core-module/src/main/java/org/simplejavamail/api/email/EmailProperty.java), [EmailConfigurationResolver](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailConfigurationResolver.java), and core [HeaderSettings](../../modules/core-module/src/main/java/org/simplejavamail/internal/email/HeaderSettings.java). The rationale column explains the architectural tradeoff; it is not a claim that every original implementation decision had a separate historical design record.

Local collection editing follows the same additive vocabulary: `withRecipients`, `to`, `cc`, `bcc` and `withReplyTo` append to existing entries,
including entries populated by `copying(...)`. Repeated calls retain order and duplicate occurrences; empty recipient/Reply-To additions do nothing.
To replace a copied Reply-To collection, use `copying(original).clearReplyTo().withReplyTo(...)`; the original Email remains unchanged.
Scalar From and bounce addresses still replace their local choice. Clearing local entries does not suppress later ordinary templates or mandatory locks.

The `ALL_RECIPIENTS` suppression selector covers To, Cc and Bcc together. Suppressing a single recipient type leaves the other types alone;
neither form suppresses the separately named delivery-recipient override. These controls remove ordinary template contributions, not the
submitted Email's recipients or mandatory recipients supplied by configuration locks.

The message being forwarded follows single-value precedence too: override, submitted Email, then default. `MAIL_TO_FORWARD` suppresses the
corresponding template contribution without removing the submitted source; subject and other fields resolve independently. Retain the selected
MimeMessage as a borrowed reference rather than reading, serializing or copying its body during governance, as defined by [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md).

Header identity is case-insensitive: `X-Route` and `x-route` name the same header during composition and template resolution. Explicit replacement
removes the earlier value collection even when its spelling differs. Retain a useful display spelling rather than requiring callers to normalize
header names themselves. This concerns composed metadata; exact EML retains its authoritative bytes.

For example, Email's `DeliveryStatusNotification` resolves as one value. Its NOTIFY set is a policy, not an attachment-like collection: an explicit preference must not acquire additional notification events by merging defaults. Recipient NOTIFY is likewise a complete set and takes precedence over the resolved Email fallback. RET/ENVID remain on the transaction-wide value; recipient policy does not split or merge those fields.

### Calendar method and text form one value (accepted 22 October 2026)

The calendar supplied through `withCalendarText(method, text)` resolves as one value: select the eligible override calendar, otherwise the
submitted Email's calendar, otherwise the eligible default calendar. Keep its method and text from the same source. Resolving them as
independent fields can create a calendar that no source supplied, or retain inherited text after suppressing its required method.

Either `CALENDAR_METHOD` or `CALENDAR_TEXT` excludes the complete calendar contribution from that template source. Default exclusions
remain separate from override exclusions, and neither removes a calendar supplied on the submitted Email. Retain the exact selector
choices for passive inspection, copying and serialization: allowing one selector does not remove a separately stored exclusion of the
other. Calendar content-transfer encoding keeps its existing independent resolution and lock rules.

This applies the existing compound-value boundary without introducing a public calendar configuration type or changing the setter/clearer
pair. It does not infer a missing method or silently discard caller calendar text. Ordinary exact-message preparation still bypasses these
templates; explicit completion remains composed output under ADR 0013. [#852](https://github.com/bbottema/simple-java-mail/issues/852)
tracks implementation for unreleased 10.0.0; released 9.3.6 resolves these components independently.

### Clear, disable, and override are different operations

OpenPGP signing and encryption follow the same whole-value resolution as S/MIME and DKIM: an override wins over the supplied Email, which wins over
a default. Encryption key-ring lists are part of that policy value, not additive Email recipients. Clearing a supplied OpenPGP configuration permits
fallback; `OPENPGP_SIGNING_CONFIG` and `OPENPGP_ENCRYPTION_CONFIG` select the ordinary per-field suppression controls. OpenPGP key configuration remains
subject to the supported snapshot contract in [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md), including retained key material; template participation does not change resource ownership.

Copying an Email retains its global and per-property suppression choices. Rebuilding a draft must not silently re-enable templates it opted out of.

- Clearing a local setting removes that choice and permits a fallback where one exists. It is not a universal "disable" operation.
- Explicit disabling is a value or a documented suppression operation, not a synonym for absence. DSN `NEVER`, for example, is an explicit notification preference.
- `ignoringDefaults(...)` and `dontApplyDefaultValueFor(...)` suppress incoming defaults for the submitted Email. The corresponding override controls suppress incoming overrides. Flags on a reference template are not another nested policy layer.
- An override has the scope and value-shape semantics documented above. It is not an enforcement boundary against application code: the submitted Email can opt out, and exact EML follows a separate preparation path.

Per-property suppression composes across helpers: repeated `dontApplyDefaultValueFor(...)` and `dontApplyOverrideValueFor(...)` calls add
exclusions, and empty calls are no-ops. `allowDefaultValueFor(...)` / `allowOverrideValueFor(...)` remove only named local exclusions;
`clearDefaultValueSuppressions()` / `clearOverrideValueSuppressions()` remove all local exclusions in their family. None changes global
ignoring flags, locks, message content or existing snapshots. This deliberately changes the earlier replacement/empty-call behavior for 10.0.0;
callers that need replacement clear first and then add the desired exclusions. Detached inspection collections are not another mutation path.

Existing APIs do not all express these distinctions consistently. Record those mismatches as migration work rather than redefining their runtime behavior through an ADR.

[ADR 0028](0028-per-email-recipient-rejection-handling.md) applies this contract to the recipient-rejection choice under #754:
`null` is unset, explicit `false` is a supplied value, and clearing permits fallback. It does not change template replacement,
suppression or the ordinary exact-EML bypass.

### Copy configuration choices, not just their resolved values

Copying preserves absence, explicit disabling and deferred resolution independently. Notification requests illustrate the distinction:
an enabled flag with no address means resolve the address later, not an absent request. An explicit false remains false through copying
and governance so a subsequent defaults pass cannot turn the request back on. An explicit address is copied without resolving another one.

The intended delivery-recipient override is part of those choices. A copy retains it independently from visible To/Cc/Bcc headers, including
ordered duplicate occurrences and recipient-specific metadata. Editing another field must not silently restore delivery to the visible recipients.
Clearing that override remains an explicit builder operation; normal governance and suppression rules still apply when preparing the copy.

## Recipient and exact-EML boundaries

Recipient-group defaults are construction-time conveniences. They resolve into the group's produced Recipient objects; governance does not maintain a live group hierarchy. Email-level fallback for a recipient-capable field remains useful, but it is not equivalent to rewriting every recipient field.

An explicit recipient S/MIME certificate wins over the governance-resolved Email certificate, even if the latter came from Mailer Email overrides. The [S/MIME preparation code](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java) documents that boundary. Preserve it and follow the same scope rule for new recipient settings. This is established behavior, not an open design question or an implicit global force operation.

Sending, rehearsing and exporting an exact Email use its separate [preparation path](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/ExactEmlSource.java), which bypasses ordinary Email defaults/overrides. This preserves the caller's submitted bytes and existing envelope choices, subject to compatible mandatory locks. Adding an envelope feature does not authorize automatic governance of exact messages or rewriting protected content.

The clarification accepted on 21 October 2026 distinguishes that automatic preparation from explicitly calling
`EmailGovernance.produceEmailApplyingDefaultsAndOverrides(exactEmail)`. The explicit operation applies ordinary templates to the parsed view
and returns a new composed Email under the same suppression and lock rules as other composition. The supplied exact Email remains unchanged;
the result does not guarantee the original bytes or the validity of existing signatures. [ADR 0013](0013-exact-eml-submission.md) owns the
authoritative-source boundary, and [ADR 0037](0037-repeatable-email-completion.md) retains this distinction for repeated completion.

## Alternatives

- **Dedicated Mailer defaults for each feature:** rejected as the general design. They duplicate configuration and force every feature to solve precedence and suppression again. DKIM convenience moves to the common mechanism, with an explicit migration for retaining property-derived defaults.
- **Always apply defaults in `buildEmail()`:** rejected for ordinary construction. The selected Mailer may supply different policy; explicit completion remains available for callers that need it early.
- **A separate policy builder mirroring Email:** not needed. It would repeat the same feature surface merely to represent partial configuration that Email already supports.
- **A generic recursive merge framework:** not selected. Scalars, collections, headers, compound policies, and recipient fallbacks have meaningfully different contracts. Keep those choices visible instead of guessing from Java field types.
- **Mutable global defaults read on every send:** rejected. A reusable factory/Mailer must not change policy because another configuration load occurred elsewhere in the application.

## Existing behavior and follow-up work

Baseline: `codex/10.0.0` at `a4eda9e6`, with the uncommitted ENVID work present during this discussion. These observations are not newly approved behavior changes.

| Area | Baseline behavior | Follow-up requirement |
| --- | --- | --- |
| Dedicated DKIM defaults | Separate builder state, a configured flag, a custom resolver, and dedicated setter/clear/accessor methods exist. | Remove the parallel route in a separately reviewed 10.0.0 migration. Preserve unrelated defaults and the ability to suppress property-supplied DKIM configuration. |
| Supplied defaults template | Replaces the automatically constructed property-backed defaults template. | Do not claim an automatic per-field overlay or silently introduce one during the DKIM cleanup. Document the migration recipe. |
| `clearEmailDefaults()` | Clears the explicit reference; governance then constructs snapshot-backed defaults when no reference is supplied. Its baseline Javadoc incorrectly said no defaults would apply. | Preserve the implementation and correct the Javadoc. Clearing the template is not per-email suppression or disabling all configured defaults. |
| Compound values and collections | Use different resolution rules; DSN currently resolves as a whole object. | Preserve existing contracts and specify the boundary for every new policy field. |
| Recipient overrides | Group-fixed values and Email overrides target different scopes; S/MIME recipient certificates retain precedence over the Email fallback. | Preserve the established precedence and apply it consistently to new recipient settings. |
| Governance coverage | Participating fields are wired explicitly; adding a getter to Email is not enough. | Audit model, copying, resolver, builder, and preparation paths for each addition. Do not assume all existing Email fields already support defaults/overrides. |

### DKIM consolidation: local implementation

The first implementation slice removes the dedicated Mailer DKIM setters, clear flag, accessors, and resolver. DKIM now uses `EmailProperty.DKIM_SIGNING_CONFIG` through the common resolution path. Incomplete signing templates are allowed; prepared messages still require a sender. Public Javadocs now describe template replacement and `clearEmailDefaults()` restoring snapshot defaults accurately.

To preserve other property-derived defaults, materialize them with `buildEmailCompletedWithDefaultsAndOverrides()`, copy the result, and replace or clear DKIM on that template. Per-email suppression remains available through the ordinary governance controls. Materialization reads configured key files and decodes inline key data first: unlike the old dedicated clear flag, it cannot bypass an unreadable file or malformed Base64 setting. Supplying an explicit replacement template skips property-derived message defaults in full. This limitation is documented in the [migration guide](https://www.simplejavamail.org/migration-notes-10.0.0.html#dkim-defaults); it does not justify adding another special-purpose policy flag.

This slice does not change recipient precedence, collection/header resolution, exact EML, or the property snapshot model. It is complete and accepted for 10.0.0. The separately implemented recipient NOTIFY/ORCPT slices use these same precedence boundaries without changing Email governance into a recursive resolver.

## Consequences and review requirements

### Receipt clearing correction (accepted 20 October 2026)

`clearDispositionNotificationTo()` and `clearReturnReceiptTo()` restore the fresh Email state: both the request flag and its
address are unset. This supersedes their earlier explicit-false behavior. Normal Email defaults and overrides can supply a
request again; existing template suppression remains the way to exclude those contributions. Clearing does not reload properties.
There are no dedicated property keys for these message-header receipt requests. Configured From/Reply-To values can provide the
destination when a request is enabled without an explicit address. Existing false request states in copied or serialized Emails
retain their meaning through internal copying and governance; the public clear operations no longer create that state.

### Selected-member ownership correction

[#757](https://github.com/bbottema/simple-java-mail/issues/757) restores this decision in ordinary clustered sends, independently of failover.
The source-history regression introduced around 8.0.0 applied the invoking Mailer's ordinary templates before choosing a member and remains in 9.3.5.
Selection must precede governance: start each candidate from the original Email, apply only its ordinary templates, then enforce applicable message locks.
No extra template layer is introduced. [ADR 0029](0029-smtp-submission-failover.md) applies the same rule when moving between configured failover Mailers.
The initiating Mailer still owns execution and observation. The released-behavior correction requires migration guidance.

[ADR 0025](0025-factory-scoped-locked-configuration.md) adds opt-in factory-scoped restrictions after this ordinary template resolution.
Suppression and template replacement still work as described for unlocked values. They cannot remove a locked setting; conflicting single-value settings fail,
while required recipient occurrences remain in the actual envelope. This is not a third public Email template and does not turn ordinary overrides into locks.

The public API stays focused: users configure a feature on Email and reuse it through one Mailer policy mechanism. The cost is explicit propagation work whenever Email grows, and careful documentation where "defaults" and "overrides" mean different things for different value shapes.

Follow the [API expansion workflow](../API_EXPANSION_WORKFLOW.md). Tests for an affected field must cover default-only, explicit-only, override, suppression, copying, and repeated use without configuration leakage. Collection and compound-value tests must assert their real merge/replacement rules, not merely that a value survives. Recipient-capable fields also need the scope cases in ADR 0001; exact EML must retain its separate contract.

The architecture and precedence are settled. Preserving property/template behavior and DKIM suppression is migration work, not an additional design question. This record does not itself implement the DKIM cleanup, broaden #736, or authorize unrelated mechanism changes. The implementation records are [#737](https://github.com/bbottema/simple-java-mail/issues/737) for shared message-security configuration and [#738](https://github.com/bbottema/simple-java-mail/issues/738) for recipient DSN policy.

## Accepted plural notification destinations (20 October 2026)

Notification destination lists are whole-value choices, not additive template contributions. Select the first nonempty eligible list from override, submitted and default, independently of the request flag. A deferred enabled request uses all resolved Reply-To recipients, falling back to From. To/Cc/Bcc and Reply-To remain additive; a plural representation does not change notification replacement semantics. Preserve the distinction between absent, explicitly disabled and enabled-but-deferred requests through copying and serialized snapshots.

## Shared configuration resolution (accepted 25 October 2026)

[ADR 0039](0039-explicit-configuration-resolution.md) introduces shared internal declaration and resolution mechanics, with Email subject selection as the first Email consumer. Subject precedence, suppression, template replacement, empty values and locks retain this record's contract. ConnectionConflictPolicy does not apply to Email governance. Other scalar fields, collections and compound policies retain their existing owners and are outside this initial migration.

Implemented for unreleased 10.0.0 under [#867](https://github.com/bbottema/simple-java-mail/issues/867); publication remains pending.

## Shared layered resolution (accepted 26 October 2026)

The 26 October extension of ADR 0039 adopts every layered Email contribution, superseding the earlier subject-only adoption limit. Scalars and atomic policies retain override/submitted/default precedence; collections, headers, suppression and mandatory locks retain their distinct rules. Public setters and ordinary buildEmail do not apply templates. See [ADR 0039](0039-explicit-configuration-resolution.md) and [#867](https://github.com/bbottema/simple-java-mail/issues/867).
