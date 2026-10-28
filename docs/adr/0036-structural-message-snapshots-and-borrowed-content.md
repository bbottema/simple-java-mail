# ADR 0036: Own message metadata while borrowing external content resources

- Status: Accepted
- Decision date: 2026-10-18
- Target: 10.0.0
- Attachment access revision: Accepted 21 October 2026; implemented and reviewed for unreleased 10.0.0 under [#821](https://github.com/bbottema/simple-java-mail/issues/821).

### Parser-opened content streams (accepted 22 October 2026)

Eager MIME attachment extraction and calendar-stream interpretation own each stream they acquire while producing the converted value. Close those streams on success and failure, preserving the original read cause and suppressed close failures through the conversion exception family. A close failure after a successful read still fails the operation. Borrowing the DataSource does not transfer ownership of a separately acquired stream to the application. Lazy extraction and caller-supplied top-level EML streams retain their existing contracts. [#828](https://github.com/bbottema/simple-java-mail/issues/828) tracks this application of the ownership decision.

Calendar charset resolution happens before acquiring a content stream, so an unsupported or illegal charset cannot leave a stream outside the
reader's cleanup scope. The existing unsupported-encoding conversion failure and charset defaults remain unchanged. Successful reads and I/O
failures retain their existing stream ownership, original causes and suppressed close failures; neither the DataSource nor a caller-supplied EML
stream becomes parser-owned. This clarification was accepted on 28 October 2026 under
[#883](https://github.com/bbottema/simple-java-mail/issues/883) for unreleased 10.0.0.

## Context and decision drivers

Applications reuse builders, Email templates and even the same Email for concurrent sends. Changing a builder must not change an already built
message's recipients, headers or envelope. An unmodifiable wrapper around somebody else's list is insufficient: that owner can still change it.

Attachment content has a different boundary. Reading application-owned DataSources during construction would introduce I/O, allocation and new
failure timing. Structural immutability must not imply that arbitrary external content has been frozen. Exact EML and retry preparation already
provide stronger, deliberately separate byte-snapshot contracts.

## Decision

### S/MIME header identity (clarified 26 October 2026)

When a retained signed-content Email is merged into its outer Email, compare header names case-insensitively. Preserve the established
exact-name precedence for every spelling: the inner header's complete value list replaces the outer list, while unrelated headers remain.
Keep the first display spelling and detach the merged metadata without modifying either source. Body and attachment merging retain their
existing rules. Disabling the merge preserves the separate views; copying and serialization preserve the resulting snapshot.

This applies while the outer and inner sources are still distinguishable. Do not reinterpret older flat maps when restoring them; ordinary
template resolution retains the previous last-matching-entry fallback described by [ADR 0002](0002-email-defaults-and-overrides.md).
The merge does not establish signature validity or rewrite authoritative exact EML bytes. [#867](https://github.com/bbottema/simple-java-mail/issues/867)
tracks this repair alongside shared configuration resolution.

The accepted ADR 0039 extension makes this ordering structural: capture keyed outer/inner declarations, resolve each logical header name,
then detach the selected lists into the message snapshot. Keep the source and eligibility facts until resolution; copying and serialization
preserve the resulting value without inventing lost provenance in older flat snapshots.

### Copy the structure the message owns

Email and recipient-group values own detached collection structures, including ordered recipient occurrences, attachment metadata, suppression
sets and both the header map and its value collections. Preserve ordering, duplicates and currently accepted values. Built values expose
unmodifiable owned collections; builder collection getters return detached copies that callers may modify without changing the builder.
Existing convenience getters that already return detached lists need not become unmodifiable.

[ADR 0037](0037-repeatable-email-completion.md) separately governs additive template merging: preserve supplied occurrences while avoiding
new equivalent template additions during repeated completion. It does not change local builder additions, structural copying, public
resource/Email equality or the borrowed-content boundary below.

As clarified on 21 October 2026, ordinary copying and supported serialization retain historical input facts: original protection results
and bytes, received delivery reports, decrypted-attachment inspection lists and retained S/MIME signed-content views/merge bookkeeping.
Defaults/overrides completion instead resolves outgoing content and configuration under [ADR 0002](0002-email-defaults-and-overrides.md).
It is not a history-preserving copy operation. Content already represented in ordinary body, header and resource fields remains outgoing
content, and this boundary does not change equality, import interpretation, byte ownership or the supported serialized form.

Recipient occurrences also matter when comparing Emails: equality must account for repeated entries and remain symmetric. Preserve the
established order-insensitive recipient comparison while distinguishing different occurrence counts; structural copying does not redefine that order rule.

The same occurrence rule applies to attachment and embedded-image lists: one attachment is not equal to two copies of it. Keep their existing
order-insensitive, metadata-based comparison without reading borrowed content. Equality also includes DKIM configuration, consistently with
the other message-signing configurations; an unsigned message is not equivalent to one configured for signing.

Copying an Email preserves its delivery-recipient override independently from its visible recipients. Keep ordered occurrences and per-recipient
metadata, so a copy used for testing remains directed to the configured test inbox. Copying structure does not consume borrowed attachment content.

Copy mutable values such as dates and supplied byte arrays at the ownership boundary. A library-created byte-array DataSource must not borrow
the caller's original array. Do not expose owned mutable storage. This does not require cloning immutable Recipients or arbitrary certificates.

Deserialization restores these guarantees even when an older snapshot contains mutable collections or aliases elsewhere in its object graph.
Preserve supported field identities and serialization identifiers. Never normalize an internal Email subtype into a plain Email or lose its
authoritative source merely to copy collections.

Java serialization retains the OpenPGP signing and encryption configuration of a composed Email, consistently with
the existing S/MIME snapshot contract. The immutable configuration values restore their construction invariants and
detach nested arrays/lists even when another object in the serialized graph refers to their storage. Signing material
includes the secret key ring and passphrase; encryption-only configuration holds recipient public key rings and cipher
preference. Treat snapshots containing signing material as sensitive, regardless of redacted automatic rendering.
Keep Email/InternalEmail serialized identities and the existing format: earlier snapshots remain readable, but omitted
OpenPGP configuration cannot be recovered. Serializing exact protected EML retains authoritative bytes and does not
apply new protection. This adds no persistent requirement marker, mandatory restoration step or general persistence policy.

The [OpenPGP replacement clarification](0031-public-api-and-internal-construction.md#openpgp-recipient-key-replacement-accepted-22-october-2026)
also applies ownership at collection replacement: prepare the complete detached list and nested arrays before changing the
builder. Rejecting missing input preserves the old selection; editing or clearing the builder cannot alter previously built
or restored configurations. Allowing unfinished builder bytes does not weaken immutable construction or deserialization.

### Referenced inline-resource metadata (accepted 23 October 2026)

Referenced inline resources retain their parsed `Content-Description` and recognized transfer encoding, consistently with ordinary
attachments. Eager and lazy import expose equivalent metadata, and existing copying, supported serialization and subsequent export carry it.
The imported source supplies decoded content: retain an ordinary output-encoding preference without declaring those bytes pre-encoded.

This clarification does not change content bytes, source-read timing, DataSource ownership, classification, occurrence order, filenames or
Content-IDs. Authoritative exact and protected representations keep their separate byte-preservation boundary. Public construction and
inspection remain the existing `AttachmentResource` API under [ADR 0031](0031-public-api-and-internal-construction.md#attachment-resource-construction).
The correction accepted under [#857](https://github.com/bbottema/simple-java-mail/issues/857) is implemented for unreleased 10.0.0.
[#856](https://github.com/bbottema/simple-java-mail/issues/856) separately owns encoding-comment interpretation.

### Attachment type metadata (accepted 22 October 2026)

An attachment's content-type parameters are metadata, including the character encoding needed to interpret its bytes. Preserve valid
parameters when materializing attachment data or wrapping a borrowed source with a name; choosing eager or lazy extraction does not choose
a different type description. Ordinary rendering retains safe non-naming parameters while applying SJM's selected filename. This changes
neither the content bytes nor ownership of an arbitrary DataSource, and adds no content read during metadata inspection, construction or copying.

[ADR 0035](0035-tolerant-reading-and-strict-production.md#attachment-content-type-parameters-accepted-22-october-2026) owns tolerant parsing,
safe production and malformed-input fallback. Existing copying and serialization carry the retained metadata; exact/protected source bytes
and stronger retry snapshots keep their own boundaries. This clarification is accepted under
[#842](https://github.com/bbottema/simple-java-mail/issues/842) for unreleased 10.0.0; its issue tracks implementation and delivery.

### Multipart attachment content ownership (accepted 22 October 2026)

Explicitly attached child multipart entities use the existing attachment resource boundary: eager extraction reads and closes its acquired
stream, while lazy extraction retains the named DataSource for later access. Both expose one resource with equivalent type metadata, including
the boundary parameter required to interpret its bytes. Classification and metadata inspection do not traverse or read its children.

Ordinary copying keeps the existing structural/borrowed-content contract; stronger serialization, exact-message and retry snapshots retain their
own byte boundaries. Existing protection inspection may still require source reads before general parsing. This clarification is accepted under
[#849](https://github.com/bbottema/simple-java-mail/issues/849) for unreleased 10.0.0; its issue tracks implementation and delivery.
[ADR 0008](0008-minimal-mime-structures-and-protection-order.md#explicit-child-multipart-attachments-accepted-22-october-2026)
owns root/body interpretation and protection order.

### Nullable custom-header metadata (accepted 22 October 2026)

The existing header map retains actual null values supplied through the public builder, independently from explicit empty Strings.
Structural snapshots, getters, ordinary copying and supported serialization preserve those choices and their existing order/identity rules.
Do not normalize or discard metadata while copying. [ADR 0035](0035-tolerant-reading-and-strict-production.md#present-empty-custom-headers-accepted-22-october-2026)
defines their common present-empty meaning at ordinary validation/rendering boundaries, including imported empty headers. That production
interpretation does not mutate the Email or change exact/protected-byte preservation. The clarification is accepted under
[#848](https://github.com/bbottema/simple-java-mail/issues/848) for unreleased 10.0.0; its issue tracks implementation and delivery.

### Failed automatic-image resolution (accepted 23 October 2026)

An automatic-image resolution pass applies its generated resources and rewritten HTML together after the pass succeeds. If a required
source cannot be resolved, retain the builder's previous HTML and image resources so retrying an unchanged or corrected draft does not
accumulate unused generated images. Preserve explicitly supplied resources and their intentional occurrences.

Keep successful-build behavior, source-resolution order and settings, strict versus optional resolution, validation and lock ordering,
Content-IDs and source ownership. This is not a general promise to roll back every later Email construction failure or undo external I/O;
it does not introduce resource deduplication or change the separate template-completion contract in [ADR 0037](0037-repeatable-email-completion.md).
The decision was accepted under [#858](https://github.com/bbottema/simple-java-mail/issues/858) for unreleased 10.0.0. The correction is
implemented; the issue tracks delivery. Public methods remain unchanged.

### Quoted automatic-image sources (accepted 27 October 2026)

An automatically resolved image source ends at the same quote that opened its HTML `src` attribute. The other quote is part of the
location: `src="it's.png"` selects `it's.png`, not a prefix file called `it`. Preserve both supported delimiter forms and the existing
named matcher groups, so the rewritten HTML retains its original delimiters around the complete generated Content-ID reference.

This correction keeps source-resolution order, opt-in settings, configured-base containment, required/optional resolution, repeated-source
handling and the failed-pass boundary above. It does not introduce general HTML parsing, entity decoding or a new malformed-HTML recovery
policy. The decision is accepted under [#871](https://github.com/bbottema/simple-java-mail/issues/871) for unreleased 10.0.0; implementation
and delivery are tracked by the issue.

### Borrow external content deliberately

Ordinary construction does not read or deep-copy application-owned DataSources, forwarded MimeMessages, certificates or provider objects.
Keep their identity and existing lifetime obligations: applications keep borrowed content available and stable while an operation consumes it.
This does not promise thread safety for mutable borrowed resources.

The `withAttachment(Path)` and `withAttachment(String, Path)` conveniences follow this same boundary. They retain the path and expose
fresh read-only streams through `Files.newInputStream` when consumed; construction and copying do not check existence or read content.
They support non-default filesystems without `Path.toFile()`. The source filename uses Jakarta Activation's existing filename-based MIME
detection, independently of an optional display name. Applications keep the filesystem open and file content stable during consumption;
serialization still captures bytes through the established attachment snapshot mechanism. Missing/unreadable content fails at consumption.

Stronger byte boundaries remain explicit: [exact EML](0013-exact-eml-submission.md) owns copied authoritative input;
[rehearsal](0014-send-time-rehearsal.md) returns its own rendered bytes; [retry preparation](0030-safe-bounded-mail-send-retries.md) freezes the
content of that retry-enabled operation. Metadata copying does not silently perform any of these operations.

A convenience reader owns the streams it opens, even when reading from an application-supplied DataSource. It closes each stream,
including on failure. Direct stream-access methods instead return a caller-owned stream. Neither contract closes the DataSource itself
or eagerly consumes its content during message construction.

### Attachment access and stream ownership

Accepted 21 October 2026: make the exception contract follow the stream-ownership boundary, applying
[ADR 0011's exception idiom](0011-transport-neutral-submission-outcomes.md#exception-idiom-for-owned-operations-and-raw-resources).
The existing mixed behavior exposes unchecked opening failures but checked reading and closing failures from the same convenience call.
Callers should be able to handle the complete operation through one failure contract.

| Public operation | Failure contract | Stream ownership |
| --- | --- | --- |
| `readAllBytes()`, `readAllData()` and `readAllData(Charset)` | Known opening, reading and closing I/O failures use `MailException` with kind `PREPARATION`; these methods declare no checked `IOException`. | SJM opens and closes the stream for the complete read. |
| `openStream() throws IOException` | Opening uses checked `IOException`, and the returned ordinary `InputStream` retains its checked reading/closing contract. | The caller consumes and closes the returned stream. |

Replace `getDataSourceInputStream()` with `openStream()` without a compatibility alias in 10.0.0. The verb communicates acquisition and
potential I/O, while `getDataSource()` remains the borrowed DataSource accessor. Keep the original I/O cause and suppression chain when
normalizing a convenience-read failure; do not turn an arbitrary application runtime exception into an I/O failure.

Construction, metadata inspection and copying remain lazy. Neither read API closes the DataSource itself, and stronger serialization,
exact-message and retry snapshots retain their existing boundaries. The consistent ownership and failure model is preferred over retaining
the previous signatures. Implementation follows this accepted decision on the development branch; 10.0.0 remains unreleased.

## Alternatives considered

- **Only wrap builder collections:** cheap, but earlier messages remain changeable through retained aliases.
- **Borrow mutable metadata and document caution:** makes template reuse and concurrency depend on invisible shared state.
- **Eagerly serialize every message:** freezes more content, but moves I/O and failures into construction and imposes the cost on everyone.
- **Deep-clone every input:** arbitrary DataSources, MimeMessages, certificates and application implementations cannot be copied reliably.

## Consequences and verification

Messages are safer to reuse. Metadata/array copying adds proportional allocation but no attachment reads, serialization passes or SMTP work.
Code that deliberately mutated returned collections or relied on builder changes leaking into earlier Emails must instead construct a replacement;
that released-behavior correction needs migration guidance. Documented internal generated facts such as Message-ID backfilling remain separate.

Verify constructor/getter/builder aliases, serialized graph aliases, ordered duplicates and concurrent reuse. Preserve borrowed-resource identity,
read counts and stronger exact/retry boundaries. Test commands and implementation progress belong in the plan, not this decision.

## Related decisions

- [0001](0001-email-configuration-scopes-and-inheritance.md) and [0002](0002-email-defaults-and-overrides.md): governance does not mutate sources.
- [0007](0007-provider-neutral-mime-boundary.md): borrowed content remains stable and repeatable during consumption.
- [0013](0013-exact-eml-submission.md), [0014](0014-send-time-rehearsal.md) and [0030](0030-safe-bounded-mail-send-retries.md): explicit content snapshots.
- [0031](0031-public-api-and-internal-construction.md): public reading, internal assembly and serialization boundaries.
- [0035](0035-tolerant-reading-and-strict-production.md): input recovery must not invent content or conceal required read failures.


## Plural notification snapshots (20 October 2026)

Notification destinations follow the same detached immutable metadata boundary as other mailbox lists. Preserve the old private scalar
field names/types solely for compatible singleton streams, and store new destination lists under distinct fields. Reading a supported older
Email upgrades its scalar destinations to singleton lists without changing request flags, serial identifiers or the forwarded-message format.
Current lists remain authoritative, including empty/deferred lists; do not substitute a legacy scalar for a present list or retain only a first
destination from a new plural value. This compatibility concerns reading older snapshots, not older readers understanding new multi-destination streams.
