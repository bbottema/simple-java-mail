# ADR 0037: Repeated completion enriches Email without repeating template additions

- Status: Accepted
- Decision date: 2026-10-21
- Target: 10.0.0
- Implementation: Implemented and reviewed locally for unreleased 10.0.0; [#768](https://github.com/bbottema/simple-java-mail/issues/768) tracks delivery and verification.
- Supersedes: Unconditional additive collection concatenation in [ADR 0002](0002-email-defaults-and-overrides.md). Other value-shape, ownership and lock rules remain applicable.

## Role-less visible occurrences (accepted 22 October 2026)

Interpret a supplied visible recipient with no role as To under [ADR 0001](0001-email-configuration-scopes-and-inheritance.md#role-less-visible-recipients-accepted-22-october-2026), rather than discarding it from the typed projections used during completion. Preserve occurrence counts and recipient metadata without mutating the supplied value or changing From/Reply-To roles. [#829](https://github.com/bbottema/simple-java-mail/issues/829) tracks this correction; the existing additive identity and lock decisions otherwise remain unchanged.

## Context

[#451](https://github.com/bbottema/simple-java-mail/issues/451) moved defaults and overrides from ordinary Email construction to Mailer
preparation. Explicit completion retained a fully populated Email for logging, helpers and other uses without sending. The earlier
[#383](https://github.com/bbottema/simple-java-mail/issues/383) established why different SMTP destinations can need different message settings;
[#757](https://github.com/bbottema/simple-java-mail/issues/757) restores selected-member ownership during clustered preparation.

A completed Email and a rehearsal's effective Email can subsequently be submitted as ordinary composed Emails. Unconditional collection
concatenation then adds the same template recipients, attachments and images again. Skipping governance for completed Emails would prevent
later enrichment and could bypass the selected sending path's requirements. Applying only overrides again would leave defaults unavailable
and would still duplicate additive override entries.

## Decision

Every ordinary completion pass can further complete the supplied Email, including when another Mailer supplies the templates. Completion
does not freeze the Email, retain its original draft, remember a previous Mailer, or introduce an exemption from later governance.

The clarification accepted on 21 October 2026 also keeps completion focused on outgoing data. Outgoing protection configurations and
delivery-notification requests participate normally; historical input results do not become part of the completed outgoing view.
[ADR 0002](0002-email-defaults-and-overrides.md#completion-resolves-outgoing-data-clarified-21-october-2026) defines the distinction from
ordinary copying and supported serialization. [#819](https://github.com/bbottema/simple-java-mail/issues/819) tracks the clarification and
the narrow correction that removes the earlier received-report carryover.
Repeated completion neither preserves a source verification verdict nor performs cryptography; protected MIME is produced later.

### Locked configuration remains binding

Applicable locks take precedence over all ordinary configuration and suppression choices. Retain [ADR 0025](0025-factory-scoped-locked-configuration.md):
reject conflicting fixed values and enforce mandatory additions, including required recipients in the actual SMTP envelope. Completion,
copying, suppression, clustering and approved failover cannot bypass those restrictions. A lock is not another ordinary override template.

### Scalars, headers and compound values retain their meaning

For scalar values, select override, supplied value, then default, subject to ordinary suppression. Later defaults fill missing values and do
not replace values materialized by a previous pass. A selected Mailer's override can replace those values. Existing absence and explicit
disabling rules remain unchanged; an empty value is not automatically absence for every field.

Headers retain case-insensitive key identity and replacement of each key's whole value collection in default, supplied, then override order.
Compound settings retain whole-value resolution. In particular, plural notification destinations, DSN policy sets and signing/encryption
configurations do not become additive merely because they contain collections.

### Add only missing template collection values

This rule applies to the existing additive fields: To, Cc, Bcc, Reply-To, delivery-recipient overrides, attachments and embedded images.

1. Retain every supplied entry and its order, including deliberate duplicate occurrences. Neither completion nor a no-template send cleans up
   the submitted collection.
2. Collect eligible override entries not already represented by a supplied entry or an earlier new override entry.
3. Collect eligible default entries not already represented by a supplied entry, a new override entry or an earlier new default entry.
4. Produce new override entries, all supplied entries, then new default entries, retaining each source's iteration order.

For example, supplied `[A, A, B]`, overrides `[A, C, C]` and defaults `[B, D, D]` produce `[C, A, A, B, D]`. Another pass with those templates
keeps the same contents and order. A different Mailer's genuinely new contributions can still accumulate. No earlier contribution is undone
based on its origin. Local builder additions continue to preserve caller-supplied duplicates.

This stability concerns governed metadata and recognized source identity. It does not promise identical generated MIME, Message-IDs, dates
or cryptographic output on separate preparations.

### Match complete recipients and conservatively identify resources

Recipient matching uses the complete recipient value: name, address, recipient role, S/MIME certificate and notification preferences.
Do not deduplicate by address alone, normalize new address equivalences, or collapse To/Cc/Bcc roles.

Resource matching is an internal merge operation, separate from public `AttachmentResource.equals()` and Email equality. A repeated
AttachmentResource is recognized directly. Different resource objects match only when their attachment metadata and recognized source
identity agree. Include the attachment name, description, Content-ID and ordinary/pre-encoded transfer-encoding choices; retain MIME-type
distinctions when comparing library-owned source representations. Different attachment/image roles remain separate collections.

Recognize source identity conservatively:

- Reuse of the same DataSource instance establishes the same borrowed source under its existing stable-content obligations.
- Library-owned file sources can compare their retained Path and filesystem identity without opening the file, resolving links or probing
  the filesystem. Do not invent path equivalences that may change meaning around symbolic links.
- Already-owned byte content can be compared without opening a DataSource or performing external I/O. Preserve defensive copying and do not
  treat arbitrary custom sources as owned memory merely because their implementation resembles a known class.
- Different opaque sources remain distinct when identity cannot be established. A common filename, MIME type, length, modification time,
  URL or sampled prefix is not sufficient proof of equal content.

Do not open streams, fetch remote resources, resolve real paths, hash external content or invoke arbitrary DataSource getters merely to
discover merge identity. Existing captured information and controlled internal representations must suffice. Unknown identity retains both
resources rather than risking silent content loss. Serialization or independent reconstruction can lose recognizable identity; the same
conservative fallback applies without adding persistent provenance or changing the serialized snapshot contract.

There will be **no caller-supplied resource identity API**. Do not add a deduplication key, callback or extension SPI for this purpose.

### Preserve suppression and sending boundaries

Retain global and per-property suppression choices through completion and copying. Repeated passes must not silently re-enable a template
contribution the Email excluded. Suppression removes eligible incoming ordinary contributions; it does not remove existing supplied values
or mandatory locked values. Reference templates do not create a nested suppression hierarchy.

Keep selected-destination governance, original-Email reuse and applicable lock propagation under ADRs 0002, 0025 and 0029. Independent sends
of the original Email remain independent. A caller who deliberately submits a previously completed result supplies its materialized values.

Sending, rehearsing and exporting the original exact Email remain on its authoritative-byte path; no ordinary template union is introduced
there. Compatible locks still apply, and incompatible requirements are rejected. As clarified on 21 October 2026, explicitly calling
`EmailGovernance.produceEmailApplyingDefaultsAndOverrides(exactEmail)` instead applies ordinary templates to its parsed view and returns a new
composed Email, following [ADR 0013](0013-exact-eml-submission.md). The supplied exact Email remains unchanged. The composed result does not
guarantee the original EML bytes or the validity of existing signatures. It follows the same suppression and lock rules as other completion.

Protected content and frozen retry snapshots retain their separate preservation contracts. Repeated completion does not rerender a frozen
retry snapshot or change the authoritative bytes retained by the original exact Email.

## Alternatives not selected

- Skipping governance after completion, or applying only overrides on later passes: loses later default enrichment and fails to resolve
  additive override repetition consistently.
- Remembering the original draft or every contributing Mailer: adds provenance and rebasing machinery instead of the agreed accumulation rule.
- Deduplicating entire submitted collections: removes deliberate caller occurrences.
- Comparing attachment names/types or reading arbitrary content: either discards potentially different documents or introduces costly I/O
  and failure timing during composition.
- Changing public equality or adding caller identity hooks: broadens API semantics beyond this merge decision; identity hooks are rejected.

## Consequences and implementation boundaries

Ordinary composition and send entry points remain unchanged. Repeated completion becomes predictable for recognized values while independent
unknown sources may still result in duplicate-looking attachments. Existing intentional occurrences, snapshot ownership and borrowed-content
lifetimes remain valid under [ADR 0036](0036-structural-message-snapshots-and-borrowed-content.md).

[#768](https://github.com/bbottema/simple-java-mail/issues/768), on the 10.0.0 project, tracks implementation and verification: focused
regressions, collection/resource matching, suppression and applicable name-lock coverage, actual send/cluster/MIME behavior, and public
documentation and migration updates. Keep execution evidence there and in commit history rather than extending this architectural record
into a run log. Local implementation and review do not imply that the unreleased changes have been published.
