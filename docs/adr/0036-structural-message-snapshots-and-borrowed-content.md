# ADR 0036: Own message metadata while borrowing external content resources

- Status: Accepted
- Decision date: 2026-10-18
- Target: 10.0.0
- Attachment access revision: Accepted 21 October 2026; implemented and reviewed for unreleased 10.0.0 under [#821](https://github.com/bbottema/simple-java-mail/issues/821).

### Parser-opened content streams (accepted 22 October 2026)

Eager MIME attachment extraction and calendar-stream interpretation own each stream they acquire while producing the converted value. Close those streams on success and failure, preserving the original read cause and suppressed close failures through the conversion exception family. A close failure after a successful read still fails the operation. Borrowing the DataSource does not transfer ownership of a separately acquired stream to the application. Lazy extraction and caller-supplied top-level EML streams retain their existing contracts. [#828](https://github.com/bbottema/simple-java-mail/issues/828) tracks this application of the ownership decision.

## Context and decision drivers

Applications reuse builders, Email templates and even the same Email for concurrent sends. Changing a builder must not change an already built
message's recipients, headers or envelope. An unmodifiable wrapper around somebody else's list is insufficient: that owner can still change it.

Attachment content has a different boundary. Reading application-owned DataSources during construction would introduce I/O, allocation and new
failure timing. Structural immutability must not imply that arbitrary external content has been frozen. Exact EML and retry preparation already
provide stronger, deliberately separate byte-snapshot contracts.

## Decision

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
