# ADR 0042: Optional resource limits for received-mail conversion

- Status: Accepted, 9 November 2026
- Target: Unreleased 10.0.0
- Implementation tracking: [#978](https://github.com/bbottema/simple-java-mail/issues/978)
- Related decisions: [0003](0003-immutable-configuration-snapshots.md), [0031](0031-public-api-and-internal-construction.md), [0035](0035-tolerant-reading-and-strict-production.md), [0036](0036-structural-message-snapshots-and-borrowed-content.md), [0041](0041-email-source-inspection-and-problems.md)

## Context

An application can restrict uploaded file sizes before converting received mail, but that does not limit the number or depth of components that Simple Java Mail interprets. Some applications also import streams whose complete size is unavailable before reading. They need a way to limit the work performed by one import while preserving the library's best-effort interpretation and source-evidence contracts.

The fluent converter already separates source selection, supplied options and a terminal operation. Adding limit arguments to every source and terminal overload would duplicate that surface and make combinations of receive options harder to use. A reusable immutable value can describe an application's choices while each actual import owns its live allowance.

## Decision

### One import configuration group

Expose core-owned `ImportLimitsConfig` with an explicit application builder, passive getters and `toBuilder()`. The existing EML, supplied-MIME and Outlook import stages accept the value through `withImportLimitsConfig(...)`, inspect supplied choices through `getImportLimitsConfig()` and restore factory inheritance through `useConfiguredImportLimitsConfig()`.

```java
ImportLimitsConfig limits = ImportLimitsConfig.builder()
    .withMaximumInputBytes(25L * 1024 * 1024)
    .withMaximumPartCount(1_000)
    .withMaximumNestingDepth(30)
    .build();

Email email = mail.converter().fromEml(inputStream)
    .withImportLimitsConfig(limits)
    .toEmail();
```

These are illustrative application values. The published fallback for every limit is zero, meaning unlimited. An omitted member inherits the captured factory choice; a positive value supplies a bound; a negative value is invalid. A reset explicitly selects the zero fallback, while `useConfigured...` restores inheritance. Group replacement discards the previous group's supplied choices. Required-null rejection happens before mutation, and inspection does not read a source or resolve effective choices.

Factory defaults use `simplejavamail.conversion.import.maximuminputbytes`, `simplejavamail.conversion.import.maximumpartcount` and `simplejavamail.conversion.import.maximumnestingdepth`. Reuse the normal snapshot, schema-bound keys, diagnostics, Spring configuration and fixed-value lock rules. Locked counterparts permit equal explicit choices and reject conflicting values, including an unlimited reset. This is import configuration, not an Email template value or Mailer sending restriction.

### One allowance per import

Resolve effective choices before terminal source consumption. The immutable value holds configuration only; each top-level conversion creates independent live counters. Selected nested MSG/EML imports and extracted protection views share the enclosing operation's allowance. They do not each receive a fresh part or depth budget.

The input-byte limit accepts at most the selected encoded size from the original raw EML/MSG source. Unknown-length acquisition can use at most one extra byte to establish that the limit has been exceeded; retained raw evidence stays within the bound. String EML uses its existing UTF-8 input meaning. Byte-array defensive copies and String encoding may already have occurred when the source was selected, so this does not claim to prevent those earlier allocations.

The part limit is checked before admitting another node to SJM's interpretation/source-capture traversal. Root depth is one; child entities and embedded messages increase depth. Repeated metadata access to one admitted node does not spend another part. A depth limit omits the deeper subtree while permitting eligible siblings; exhausting the shared part allowance stops further admissions and produces bounded reporting rather than enumerating every omitted part.

The initial MIME root is counted once. A selected extracted view consumes a node at the same logical depth as the representation it opens, sharing the enclosing allowance; rereading an already admitted occurrence for retained content does not charge it again. For Outlook, root metadata is the first node. Represented plain/HTML bodies, retained original RTF or native body parts and each attachment occurrence consume child nodes. An embedded message root consumes a further level beneath its attachment. These are SJM materialization and capture boundaries, not counts of allocations already performed by a native parser.

A caller-supplied `MimeMessage` has no reliable original encoded length. Reject a selected finite input-byte limit before consuming that conversion, with guidance to use original raw input or select traversal limits. Do not serialize a live message to manufacture its alleged original size. MIME parsed internally from checked raw input carries that operation context.

### Preserve best-effort results and ownership

When a bound stops an Email import, preserve available normal content and source evidence and report `IMPORT_LIMIT_EXCEEDED` through `Email.getProblems()`. A raw-input stop can produce a source-only inspection Email with an incomplete captured prefix. Never present that prefix as a complete source, expose incomplete attachment content as complete, or infer verification/decryption success from a truncated representation.

Fresh protected-byte capture for a bounded arbitrary supplied MIME message must not read beyond the content that its import could fully visit. When complete protected bytes were not available, `getOriginalProtectedMessage()` can remain null, including for protected input; an absent limit Problem alone does not establish a completed projection. Complete raw EML bytes already owned remain available immediately and exact even if later traversal stops. Failed/unavailable inspection does not manufacture uncaptured bytes, and byte availability remains independent of verification/decryption facts. Normal complete bounded imports can retain their full protected representation after traversal; unlimited and exact-source behavior keep their existing contract.

A bounded import of an unsaved supplied `MimeMessage` keeps Date and Message-ID as observed before diagnostic serialization: an absent value remains absent on the converted Email even if generating that diagnostic snapshot adds metadata to the native message.

For an explicitly limited retained inner view, use the actual parse events to establish whether a plain-text, HTML or calendar body was materialized. When a limit stops the view before any such body is materialized, keep its partial Email for inspection and use the existing separate-view presentation so completed outer content remains available. A fully visited inner view with absent alternatives still follows [ADR 0040](0040-retained-protected-content-merging.md); an independently omitted later attachment does not suppress a body already materialized. This preserves completed neighbors without introducing per-field body fallback.

Limit filtering must not establish message-level protection from the number of attachments left in a partial list. Process ordinary retained attachments as independent children. Original native source structure and header-established envelope facts keep their authority; a remaining protected child does not by itself turn a partial ordinary parent into an encrypted or signed message envelope.

Preserve existing stream ownership, original/suppressed failures and the propagation of cancellation, Errors and unrelated application failures. Limit exhaustion has its own typed boundary; it is not recognized from exception prose or a catch-all recovery rule. Configuring limits does not acquire otherwise deferred attachment content. Later application-initiated reads retain their existing borrowed-resource contract.

Source links, original input identities and observations follow ADR 0041. Ordinary copies and supported serialization preserve captured evidence without another import; outgoing completion keeps its separate new-output/history boundary. The import policy does not become outgoing message configuration.

### Target and resource boundaries

The policy applies to `toEmail()` and `toEmailBuilder()`. Combined Outlook-to-MIME/EML conversion applies it during the delegated import. If raw input cannot be acquired completely, those artifact operations retain their recognized no-value failure rather than silently exporting a source-only Email. Existing recoverable component omissions can still accompany a usable completed artifact and its observations.

Direct EML/MIME transformations that bypass Email import reject an explicitly supplied incompatible import group before consumption. Factory import defaults remain inactive on those targets. Exact-EML relay construction retains its authoritative-byte contract and requires application-side acceptance limits at its own input boundary.

CLI copying and reply message-file arguments create a `MimeMessage` that the builder then imports. A finite raw-input limit is rejected before reading file content on those routes, preserving the supplied-MIME applicability rule. EML forwarding only borrows the MIME message, so ordinary import defaults do not activate there; MSG forwarding performs a combined MSG import and applies its supported limits. Message-file conversion uses the request's captured factory, including applicable part/depth settings. These routes add neither an object-valued CLI option nor a separate conversion command. Direct public EML-to-MIME bypass targets also leave ordinary import defaults inactive.

The limits cover controlled SJM acquisition and traversal boundaries. Caller-created objects and native parsers/providers/crypto libraries may allocate before control returns to SJM. This decision adds no total-heap, wall-clock, expanded-content or separate retained-evidence byte guarantee, disk spool or source resolver. An unavailable native enforcement point must be described honestly rather than hidden behind a stronger resource claim.

## Alternatives and consequences

Application-only limits leave tree interpretation inside SJM without a corresponding control. Always-on finite defaults would restrict existing valid inputs for applications that have not selected a budget. Optional limits retain established convenience while allowing the application to select its acceptable workload.

A single options value keeps source and result signatures stable and permits reuse across concurrent imports. Counters, exceptions and source ownership remain internal execution responsibilities. The cost is new configuration/integration obligations and explicit documentation of unsupported raw-size checks and native allocation boundaries; the [API expansion workflow](../API_EXPANSION_WORKFLOW.md) governs those implementation obligations.
