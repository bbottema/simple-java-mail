# ADR 0036: Own message metadata while borrowing external content resources

- Status: Accepted
- Decision date: 2026-10-18
- Target: 10.0.0

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

Copy mutable values such as dates and supplied byte arrays at the ownership boundary. A library-created byte-array DataSource must not borrow
the caller's original array. Do not expose owned mutable storage. This does not require cloning immutable Recipients or arbitrary certificates.

Deserialization restores these guarantees even when an older snapshot contains mutable collections or aliases elsewhere in its object graph.
Preserve supported field identities and serialization identifiers. Never normalize an internal Email subtype into a plain Email or lose its
authoritative source merely to copy collections.

### Borrow external content deliberately

Ordinary construction does not read or deep-copy application-owned DataSources, forwarded MimeMessages, certificates or provider objects.
Keep their identity and existing lifetime obligations: applications keep borrowed content available and stable while an operation consumes it.
This does not promise thread safety for mutable borrowed resources.

Stronger byte boundaries remain explicit: [exact EML](0013-exact-eml-submission.md) owns copied authoritative input;
[rehearsal](0014-send-time-rehearsal.md) returns its own rendered bytes; [retry preparation](0030-safe-bounded-mail-send-retries.md) freezes the
content of that retry-enabled operation. Metadata copying does not silently perform any of these operations.

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
