# ADR 0002: Email defaults and overrides

- Status: Accepted architectural direction; existing behavior recorded separately below
- Decision recorded: 2026-09-16; this is not the introduction date of the existing mechanism
- Applies to: The 10.0.0 API and subsequent API additions
- Implementation status: Existing mechanism; DKIM consolidation and the related recipient-scope work are complete and accepted on 17 September 2026 for 10.0.0, not yet released
- Related decision: [ADR 0001: Email configuration scopes and inheritance](0001-email-configuration-scopes-and-inheritance.md)
- Implementation tracking: [#737 — Unify DKIM and S/MIME configuration through Email defaults and overrides](https://github.com/bbottema/simple-java-mail/issues/737)

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

The current constructor uses either the supplied defaults Email or the snapshot-derived defaults Email. It does **not** automatically fill a supplied defaults template's missing fields from the property snapshot. Repeated `withEmailDefaults(...)` calls replace the reference rather than accumulating templates. Any change to that behavior needs its own reviewed migration.

## Resolution contract

Resolution depends on the shape of the value. The following describes current behavior, subject to the submitted Email's suppression settings.

| Value shape | Resolution | Why keep the distinction explicit? |
| --- | --- | --- |
| Single value, such as subject or signing configuration | First non-null value from override, submitted Email, then default. | An explicit value replaces the fallback rather than being combined with it. |
| Collections, such as attachments and TO/CC/BCC recipients | Collect override entries, submitted entries, then default entries. | Reusable additions must not silently discard a message's attachments or recipients. An "override" template does not replace the whole collection. |
| Headers | Combine keys; for the same key, replace the entire value collection in default, submitted, then override order. | Different header keys can coexist, but conflicting values for one key have a clear winner. |
| Compound configuration values | Resolve at the declared value boundary, not recursively by every member field. | Combining parts of separate signing or notification policies can create a configuration neither caller requested. |

These rules are implemented by [EmailGovernanceImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/EmailGovernanceImpl.java), [EmailProperty](../../modules/core-module/src/main/java/org/simplejavamail/internal/config/EmailProperty.java), and the [resolution helpers](../../modules/core-module/src/main/java/org/simplejavamail/internal/util/MiscUtil.java). The rationale column explains the architectural tradeoff; it is not a claim that every original implementation decision had a separate historical design record.

For example, Email's `DeliveryStatusNotification` resolves as one value. Its NOTIFY set is a policy, not an attachment-like collection: an explicit preference must not acquire additional notification events by merging defaults. Recipient NOTIFY is likewise a complete set and takes precedence over the resolved Email fallback. RET/ENVID remain on the transaction-wide value; recipient policy does not split or merge those fields.

### Clear, disable, and override are different operations

- Clearing a local setting removes that choice and permits a fallback where one exists. It is not a universal "disable" operation.
- Explicit disabling is a value or a documented suppression operation, not a synonym for absence. DSN `NEVER`, for example, is an explicit notification preference.
- `ignoringDefaults(...)` and `dontApplyDefaultValueFor(...)` suppress incoming defaults for the submitted Email. The corresponding override controls suppress incoming overrides. Flags on a reference template are not another nested policy layer.
- An override has the scope and value-shape semantics documented above. It is not an enforcement boundary against application code: the submitted Email can opt out, and exact EML follows a separate preparation path.

Existing APIs do not all express these distinctions consistently. Record those mismatches as migration work rather than redefining their runtime behavior through an ADR.

## Recipient and exact-EML boundaries

Recipient-group defaults are construction-time conveniences. They resolve into the group's produced Recipient objects; governance does not maintain a live group hierarchy. Email-level fallback for a recipient-capable field remains useful, but it is not equivalent to rewriting every recipient field.

An explicit recipient S/MIME certificate wins over the governance-resolved Email certificate, even if the latter came from Mailer Email overrides. The [S/MIME preparation code](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java) documents that boundary. Preserve it and follow the same scope rule for new recipient settings. This is established behavior, not an open design question or an implicit global force operation.

Exact EML takes a separate [preparation path](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/ExactEmlSource.java) that bypasses Email defaults/overrides. This preserves the caller's submitted bytes and existing envelope choices. Adding an envelope feature does not authorize automatic governance of exact messages or rewriting protected content.

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

To preserve other property-derived defaults, materialize them with `buildEmailCompletedWithDefaultsAndOverrides()`, copy the result, and replace or clear DKIM on that template. Per-email suppression remains available through the ordinary governance controls. Materialization reads configured key files and decodes inline key data first: unlike the old dedicated clear flag, it cannot bypass an unreadable file or malformed Base64 setting. Supplying an explicit replacement template skips property-derived message defaults in full. This limitation is documented in the [migration guide](../../MIGRATION-10.0.md#dkim-defaults-use-email-templates); it does not justify adding another special-purpose policy flag.

This slice does not change recipient precedence, collection/header resolution, exact EML, or the property snapshot model. It is complete and accepted for 10.0.0. The separately implemented recipient NOTIFY/ORCPT slices use these same precedence boundaries without changing Email governance into a recursive resolver.

## Consequences and review requirements

The public API stays focused: users configure a feature on Email and reuse it through one Mailer policy mechanism. The cost is explicit propagation work whenever Email grows, and careful documentation where "defaults" and "overrides" mean different things for different value shapes.

Follow the [API expansion workflow](../../API_EXPANSION_WORKFLOW.md). Tests for an affected field must cover default-only, explicit-only, override, suppression, copying, and repeated use without configuration leakage. Collection and compound-value tests must assert their real merge/replacement rules, not merely that a value survives. Recipient-capable fields also need the scope cases in ADR 0001; exact EML must retain its separate contract.

The architecture and precedence are settled. Preserving property/template behavior and DKIM suppression is migration work, not an additional design question. This record does not itself implement the DKIM cleanup, broaden #736, or authorize unrelated mechanism changes. See the [implementation slices](../../03_SMTP_ROBUSTNESS_IMPROVEMENT_PLAN/phase-4-modern-esmtp/06c-homogeneous-email-configuration.md) for progress.
