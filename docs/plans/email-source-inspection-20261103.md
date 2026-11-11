# Source inspection on Email

Retired after #961 delivery, 4 November 2026. This is a historical specification/implementation packet, not current status. The accepted
contract is preserved in [ADR 0041](../adr/0041-email-source-inspection-and-problems.md); current delivery and deferred work are in the
[durable roadmap](email-inspection-roadmap-20261104.md). This exact retired path is registered for guarded later cleanup.

Coordination/status entry point: [Email inspection roadmap](email-inspection-roadmap-20261104.md). This file retains the original local
implementation packet; the roadmap and linked accepted/revised contracts determine the current direction.

Historical packet: the revised source-content/recovery/artifact contracts are now implemented in the working tree. Their final-current
verification, acceptance and delivery remain in the coordination plan; the older API/evidence below does not certify that revision.

Latest direction, 4 November: selective original payload retention for failed/unrepresented or meaningfully changed content, with default
best-effort conversions and no SourceContentOmissionPolicy. The earlier blanket source capture/default-refusal proposals are superseded;
see the [revised omission/retention contract](email-omissions-and-retention-20261104.md). This older packet is not evidence that the revision
has been implemented or delivered.

Status: design and execution approved by the maintainer on 3 November 2026 under [#961](https://github.com/bbottema/simple-java-mail/issues/961). The accepted source/metadata and root problem contracts are implemented and checked locally; the review/delivery workflow remains unfinished. The initial recovery cases concern headers; the reporting contract is exposed on every resulting Email and survives ordinary copying. Current producers record IMPORT observations; COPY/CONVERSION are categories for concrete operation-specific recoveries, not a claim that every transformation already reports them. The governing philosophy is already accepted in ADRs 0006, 0008, 0013, 0035 and 0036; ADR 0041 records the accepted concrete rules.

## Execution impact map

- Baseline: main `0a568fe4793a7c81b279da36ddf5a22d60175f05`, website `93a158e74a99a5381b4453ea46e050ee89a2f06e`. Preserve the three staged Angus research paths, all unrelated root/website edits, prior local README/version-policy edits and registered worktrees.
- API/snapshots: core Email, five new read contracts and core-owned serializable snapshots. Internal builder bridging avoids adding source-fact setters to application builder APIs. Both new fields need explicit merge/copy ownership and old-stream defaults.
- Main paths: MimeMessageParser/DecodedHeader -> MimeMessageEmailMapper; ConfiguredEmailConverterImpl preprocessing and Outlook conversion; ExactEmailBuilderImpl; InternalEmailPopulatingBuilder/EmailPopulatingBuilderImpl and EmailStartingBuilderImpl; outgoing completion in EmailGovernanceImpl.
- Optional path: existing Outlook source result and its native header data. Snapshot metadata into core-owned values so generic inspection does not retain parser or optional-module implementation objects.
- Risk cases: repeated case-interleaved headers, discarded values with unknown charset, typed Subject/Date/address/receipt recovery, no Message-ID and A-to-B-to-C IDs, protected source ownership and qualified inner findings, exact bytes, optional-module absence, borrowed-resource purity, copying/serialization and completion dropping input history.
- Required surface alignment: ADRs 0006/0008/0013/0035/0036/0037/0040/0041 and index; README/release history, website inspection/security/migration, applicable Journal/case-study/downloaded examples, OpenRewrite inventories. No property, Spring setting, builder option or CLI configuration field is introduced; generated Javadocs/metadata packaging still need checking.
- Verification order: establish behavioral red through fresh reactor outputs, implement and run focused public-path cases plus existing controls on Java 21 and real Java 11, then API/core-only packaging/Javadocs and applicable website/example checks. Scratch is isolated under `.local/email-source-inspection-20261103`; inspect Surefire XML, max two Maven processes, no cleanup deletion or unrelated staging.

## Caller usage

Conversion continues through the existing gateways. No inspection flag or separate parser is required.

```java
Email email = mail.converter().fromEml(emlBytes).toEmail();

String subject = email.getSubject();
String body = email.getPlainText();

EmailSourceData source = email.getSourceData();
List<String> comments = source.getHeaderValues("Comments");
List<String> received = source.getHeaderValues("Received");

for (EmailSourceHeader header : source.getHeaders()) {
    System.out.println(header.getName() + ": " + header.getRawValue());
}

for (EmailProblem problem : email.getProblems()) {
    System.out.println(problem.getOrigin().getMessageId() + " / "
        + problem.getOperation() + " / " + problem.getLocation()
        + ": " + problem.getMessage());
}
```

An MSG conversion carries the existing Outlook metadata with the Email:

```java
Email email = mail.converter().fromOutlookMsg(msgFile).toEmail();
OutlookMessageData outlook = email.getSourceData().getOutlookMessageData();

String rawHeaders = outlook.getRawHeaders();
String lastModifier = outlook.getLastModifierName();
String properties = outlook.getPropertyListing();
```

The old `OutlookEmailConversionResult` methods remain usable. Its `getOutlookMessageData()` and the built Email's source data expose the same captured metadata. Ordinary `fromOutlookMsg(...).toEmail()` also retains it, so callers do not need to keep a conversion wrapper solely to inspect their Email later.

## Exact public additions

The new contracts belong in `org.simplejavamail.api.email`, in core. `OutlookMessageData` remains the existing core contract in `org.simplejavamail.api.outlook`.

```java
// Added to Email:
@NotNull
public EmailSourceData getSourceData();

@NotNull
public List<EmailProblem> getProblems();

public interface EmailSourceData extends Serializable {
    @NotNull List<EmailSourceHeader> getHeaders();
    @NotNull List<String> getHeaderValues(@NotNull String name);
    @Nullable OutlookMessageData getOutlookMessageData();
    @Nullable EmailSourceData getUnwrappedContent();
}

public interface EmailSourceHeader extends Serializable {
    @NotNull String getName();
    @Nullable String getRawValue();
}

public interface EmailProblem extends Serializable {
    enum Operation { IMPORT, CONVERSION, COPY }
    enum Code { VALUE_NOT_DECODED, FIELD_NOT_INTERPRETED, INTERPRETATION_WARNING, SIGNATURE_INVALID, PROTECTION_FAILED }

    @NotNull EmailProblemOrigin getOrigin();
    @NotNull Operation getOperation();
    @NotNull Code getCode();
    @NotNull String getLocation();
    @Nullable String getSourceValue();
    @NotNull String getMessage();
}

public interface EmailProblemOrigin extends Serializable {
    @NotNull UUID getReference();
    @Nullable String getMessageId();
}
```

These are operation-produced read contracts. Source facts and problems are captured by the operation that produces the Email. No application-facing source-data/problem builders or setters are added. Applications create outgoing content using the existing Email builders.

## Problems belong to Email

`Email.getProblems()` is the single discovery point for recoverable problems observed while producing that represented Email. It returns an immutable, never-null list for plain MIME/EML, MSG, exact inspection, protected-content conversion, and copies. An empty list means no problem was recorded during those operations; it is not a validity certificate or a claim that every byte, signature or attachment was inspected.

`EmailSourceData` contains received evidence only. It has no separate problems collection. Root reporting therefore remains available when there is no received source snapshot, including a problem observed during an Email-to-Email transformation or a recoverable copying step.

Operation identifies where a finding originated:

| Operation | Meaning |
| --- | --- |
| `IMPORT` | Reading external MIME/EML/MSG or interpreting its materialized content while producing Email, with or without protection. |
| `CONVERSION` | A transformation of existing mail into another represented Email, including outgoing completion when it actually records a recoverable problem. |
| `COPY` | A newly observed recoverable problem in a copy operation. A normal copy does not create one merely by copying. |

The codes above cover the initial header/field recovery cases. Add further codes only for concrete, specified recoveries. Do not label an expected transformation as a failure: ordinary copying of exact Email deliberately creates composed content and does not automatically produce a signature-invalid or lost-source problem.

Ordinary copying preserves existing entries, their operation, location and original source value. It does not relabel inherited `IMPORT` findings as `COPY`, reparse the source, reread content, rerun crypto, or append the same findings on every copy/build. If a future supported copy recovery genuinely records another problem, append that observation once with operation `COPY`.

### Identify the message where the problem arose

Every problem retains an immutable `EmailProblemOrigin` captured from the message being processed when that observation first arose. Its Message-ID belongs to that input, not whichever later Email happens to carry the problem. For `COPY` and Email-to-Email `CONVERSION`, capture the input Email's ID; an ID selected for the output does not relabel the input observation. During import, capture the available received identity before processing can generate, replace or backfill an outgoing ID.

The reference is a library-generated UUID identifying that particular input snapshot/processing context. Problems from the same input context share it. Copies and serialization preserve the value. Separate imports receive separate references even if their inputs claim the same Message-ID or contain identical bytes. It is an observation reference, not an email identity or content hash; it does not set or change `Email.getId()`.

`getMessageId()` retains the observed value under the existing input's ID-selection policy and may be null when absent. It is not a uniqueness or validity guarantee; original repeated/empty/malformed header occurrences remain inspectable in source data. Never generate a Message-ID solely to populate a problem's origin, fill an old origin with an ID assigned later, or deduplicate findings by Message-ID.

For an observation about a retained inner message, capture that message's available identity and qualify its location in the containing result. An inner message without a supplied ID does not acquire its parent's ID as a purported inner identity. An SJM-generated MIME wrapper ID does not become a purported received Message-ID. The stable reference and qualified location remain available when no original ID is established. Container-level processing failures belong to the container's input origin. Parent aggregation and copying preserve each existing origin unchanged.

| Message flow | Origin shown by the carried problem |
| --- | --- |
| Import input A reports `<a@example.org>` and produces a problem | A's reference and `<a@example.org>`, operation `IMPORT`. |
| Copy A while selecting output ID `<b@example.org>` | The inherited problem still identifies A and retains operation `IMPORT`. |
| Copy B again while selecting output ID `<c@example.org>` | The inherited problem still identifies A, once. |
| A genuinely new recoverable problem arises while copying input B | A separate observation identifies B and `<b@example.org>`, operation `COPY`; the inherited A problem is unchanged. |
| Import a message with no supplied Message-ID | Its reference is available; its origin Message-ID remains null through later copies or output ID generation. |

```java
Email edited = mail.emailBuilder().copying(original)
    .fixingMessageId("<edited@example.org>").buildEmail();

edited.getId(); // <edited@example.org>
EmailProblem inherited = edited.getProblems().get(0);
inherited.getOrigin().getMessageId(); // the input identity captured when it first arose
inherited.getOrigin().getReference(); // the same stable reference as before copying
```

Store only the small origin value, not a reference to a live Email, MimeMessage, converter or parser object. An inherited problem therefore neither changes when later IDs change nor keeps the entire ancestral message graph alive.

Problems are historical observations, not current validation results. Editing a copied subject leaves the original subject-decoding finding intact. The entry still says `IMPORT` and retains the original value; it does not claim that the edited subject is undecodable. Mailer validation continues to assess current outgoing content independently.

Existing delivery-report warnings and protection outcomes remain available in their feature-specific read contracts. Review their root discoverability when implementing this reporting model. Record typed root problems at the feature boundary where the condition is known; do not classify existing warning strings by parsing their prose, turn reported delivery failure into a parsing failure, or infer failed verification from an unknown inspection result. Root summary and specialized detail must describe the same observation consistently.

The getter captures problems known when that Email is produced. It is not a live log for later attachment reads or sends. A conversion to EML bytes, String or MimeMessage cannot append findings to an already immutable input Email; any additional recoverable output-conversion reporting needs a result owned by that operation. This proposal adds the common Email read contract without introducing a generic operation-result framework.

## Header meaning

`getHeaders()` returns an immutable list of all available source root-header occurrences: structural, custom, recognized and unrecognized. Preserve source order, duplicates, original name capitalization and values as supplied by the source reader. It includes `From`, `To`, `Date`, `Subject`, `Received`, `Comments`, `Keywords`, MIME headers and other fields currently excluded from the outgoing map. A nullable value supplied by an existing provider remains distinguishable from an absent occurrence; it does not justify dropping that occurrence.

`getRawValue()` performs no encoded-word decoding, unfolding or outgoing validation. For MIME/EML it records the header value exposed by Jakarta Mail. For Outlook it uses the existing native source-header representation, including its ordered raw header text where available. It never manufactures a native MSG header from a typed MAPI property. If native raw order is unavailable, preserve the specialist reader's available order without claiming to recover missing interleaving.

`getHeaderValues(name)` compares names case-insensitively and returns all matching raw values in occurrence order. No match produces an empty immutable list. A present empty header produces one empty value. An empty or null lookup name is an argument error, consistent with the existing Outlook lookup.

Capture root source headers before protection preprocessing, typed interpretation, normalization or header exclusion. `Email.getHeaders()` retains its current role in ordinary composition and custom-header merging. Source data is available for inspection without automatically becoming content for a new outgoing message.

This is available header text, not an exact-wire-byte claim. Authoritative exact/protected bytes retain ADRs 0013/0008's existing ownership contracts. In particular, serializing a supplied live MimeMessage is not a way to reconstruct original source evidence.

## Protection and source ownership

`getUnwrappedContent()` links to the next content view only when the existing S/MIME/OpenPGP pipeline actually materializes one. Each source view owns its own headers and Outlook metadata. Header occurrences from different views are never flattened together. Repeated unwrapping can produce a chain when existing processing already exposes those stages; this API does not initiate new recursive decryption.

The containing Email reports the problems encountered while constructing its represented content, including materialized content used from an unwrapped view. Locations identify that view, for example `source/unwrapped/Subject` or `attachment[1]/report`. A separately inspectable retained inner Email also owns the observations made while constructing it. Parent aggregation qualifies the location and records an observation once, rather than asking callers to traverse every source node or replaying it during copying. A problem about an independent attachment remains located on that attachment and does not establish a parent signature/decryption result.

For legacy retained Outlook S/MIME content, the unwrapped source view is shared with the retained inner Email's source data. For a protected EML parsed directly through an effective clear root, the root source remains the received wrapper and its unwrapped view contains the materialized clear header data. A generated Outlook-derived retained MIME view remains a generated view, not proof of the original wire representation. Source-text availability and protection outcomes remain separate facts.

Missing keys, unavailable optional processing or unsupported materialization leave this link null while retaining the established outer facts/artifacts. The new metadata access does not load an optional module, perform cryptographic work or acquire content in a getter.

## Concrete interpretation behavior

The first increment covers header/source metadata and header interpretation failures. It does not claim that every existing body/resource failure is now recoverable. A later content-recovery change must specify how its unavailable bytes or undecodable part remain accessible, rather than catch the failure and return apparently complete content.

| Input | Convenient Email view | Source data |
| --- | --- | --- |
| Readable ordinary EML | Existing interpreted subject, addresses, dates and body. | Every available root header occurrence, including ones omitted from outgoing composition. |
| `Comments: =?X-NO-SUCH-CHARSET?Q?note?=` | Readable neighbors import normally. The outgoing custom-header map keeps its existing exclusions. | Original Comments value is present. Do not decode an otherwise unused value merely to classify or discard it. No problem is invented when interpretation was not needed or attempted. |
| Undecodable generic header | Existing original-value recovery remains. | Original raw value; root Email records `VALUE_NOT_DECODED` for the attempted interpretation. |
| Undecodable Subject | Retain the whole original value as the subject text, safely unfolding legal continuations. Do not partially decode some words or substitute guessed characters. | Original header occurrence; root Email records `VALUE_NOT_DECODED`, location `Subject`. This deliberately replaces the current whole-import failure for this case. |
| Impossible or uninterpretable Date | Sent date remains unavailable under the existing date interpretation policy; neighboring fields remain usable. | Original Date plus `FIELD_NOT_INTERPRETED`, location `Date`. |
| Malformed address field | Existing raw-Recipient/source fallback and field-specific absence rules apply; no fabricated mailbox. | Original complete field plus `FIELD_NOT_INTERPRETED` when recovery was needed. |
| Undecodable/uninterpretable receipt or bounce field | Keep independently interpretable occurrences; an unusable occurrence does not establish a notification destination or bounce recipient. | Complete offending source value plus the corresponding problem; readable neighbors remain inspectable. |
| Previously composed Email | Its current getters describe composed content. | An empty source snapshot. It does not claim that current outgoing fields were received. |

For these initial recovery cases, problems have operation `IMPORT`. A source header is not a problem merely because it is unfamiliar. `getLocation()` identifies the affected view, field and, when relevant, occurrence, for example `To[1]`. `getSourceValue()` retains the complete offending source text, including mixed decodable/undecodable words. A problem message explains the failure without requiring applications to parse the message for its category. Do not serialize live parser/exception/resource graphs just to retain the message.

Catch only recognized interpretation failures at their actual field boundaries. Failure to acquire input, cancellation and unrelated application/programming exceptions retain their error paths. No global permissive setting or catch-all exception recovery is added.

## Copying, completion, resources and compatibility

- An imported Email owns an immutable source metadata snapshot. Getters neither re-read a file nor consult a live MimeMessage or Outlook parser object.
- Ordinary copies and supported serialization retain source history and captured problem entries, including their original input identity/reference. Editing a copied subject, header or Message-ID changes composed content; it does not rewrite the captured source or historical origins/findings. Repeated copies do not grow the problems list without a newly observed recovery.
- Fresh composition, replies and newly composed forwards start with their own source/problem history. Forwarded/attached source content keeps its own owner; history is not blindly attributed to the new message.
- Defaults/overrides completion is the existing new-output boundary, not a history-preserving copy. It drops received source history and inherited processing problems from the completed outgoing Email, consistently with current report/protection facts. New recoverable problems observed during that completion belong to its result with operation `CONVERSION`. The input Email and its recorded problems remain available to the application.
- Source data never supplies routing, enables outgoing protection, bypasses validation or gets automatically rendered into MIME. Historical problems do not become a new automatic send blocker for later edited content.
- Existing exact/protected bytes and attachment borrowing/read/close contracts remain intact. Metadata capture does not eagerly read attachments or serialize a forwarded message.
- Implement snapshot values in core/main using the existing core `OutlookMessageData` contract. Do not make copying/deserialization of generic Email metadata require an Outlook parser object or an optional module implementation class; adapt the existing Outlook snapshot implementation accordingly.
- Restore older serialized Email values with an empty source snapshot and empty problems list. Do not invent original received headers or successful inspection from their possibly edited, normalized outgoing header map.
- Classify both new stored Email fields in the explicit merge ownership inventory: source data is outer source history, and problems are the captured operation observations for the represented result. Do not merge raw headers with the inner source view or reaggregate problems at every build/copy. The converter supplies the qualified problem snapshot and the unwrapped link preserves source relationships.

## Implementation and verification obligations

1. Add the core source read contracts, root `Email.getProblems()` and immutable problem/origin snapshots, including operation provenance, stable reference/observed Message-ID, immutable/ordered lookup and serialization behavior.
2. Capture original MIME/EML headers before the existing preprocessing stages. Capture native Outlook headers/metadata from the existing reader and attach them on every conversion path.
3. Attach materialized unwrapped source views without new protection processing, and wire source data and root problem/origin snapshots through internal construction, copying, restoration and completion. Capture input identity before output ID selection/backfilling. Cover `copying(Email)`, `copying(MimeMessage)` and `copying(EmailPopulatingBuilder)` without duplicating findings from their delegated import/build steps.
4. Reorder unused-header handling and recover the specified recognized header interpretation failures, recording root problems with operation and affected source location. Review existing feature-specific warnings/outcomes for consistent root reporting. Update older strict-header expectations only where this contract deliberately changes them.
5. Verify ordinary/static/fluent MIME/EML/MSG, blank/absent/empty/repeated/case-interleaved headers, unknown codecs and mixed encoded words, malformed dates/addresses/receipts, supplied-provider failures, protected/separate/merged views, copying/edits/serialization and ordinary export. Assert both information availability and absence of source-header replay. Assert problem ownership/operation, parent-qualified locations, stable A-to-B-to-C origins despite changed/cleared/generated IDs, missing/repeated/duplicate IDs, independently observed COPY origins, no repeated-copy growth, preservation after edits, and the new-output completion boundary. Preserve tests for content/I/O/programming failures outside the accepted recovery cases.
6. Reconcile public Javadocs, existing Outlook result documentation, ADRs 0006/0008/0013/0035/0036/0040, website inspection and migration guidance, Journal/case-study/downloaded examples, and OpenRewrite inventories. No existing Outlook entry point is removed or renamed by this proposal.

Root owns the implementation and its ADR/documentation alignment under #961. The accepted source/problem APIs and header recovery are now implemented locally. Execution records are bounded to `.local/email-source-inspection-20261103`; keep dated results there and in the maintainer conversation, not in GitHub issue/commit prose. Public configuration, Spring metadata, CLI flags and template selectors are unchanged because these are converter-owned read facts. CLI artifacts/Javadocs and the selected website are checked against the updated sources. Journal/case-study/downloaded examples have no conflicting source-inspection usage in the scoped search; existing converter entry points remain supported.

The prior agent stopped at implementation and interpreted the latest instruction as excluding delivery. That was an agent interpretation, not an explicit maintainer prohibition on committing or publishing; it must be reconciled with the standing complete-review-workflow instructions. No delivery or cleanup has occurred. Keep #961 open/In Progress while review/delivery remains pending. Preserve the unrelated staged Angus research, dirty root/website work and prior local version-policy changes. The exact scratch root is registered in the guarded cleanup script; its selected-target dry run performs no deletion and uses no dependency junction.

## Unfinished review workflow

### Body/attachment design steering, 4 November 2026

The maintainer rejected adding multiple recovery states to normal AttachmentResource values. Successfully represented content stays in
the current body/attachment/embedded-image structure. Failed source content is retained separately in problem records, including its
available data and source identity. The generic source-parts sketch and AttachmentResource.getContentState() sketch are withdrawn.
ADR 0041 records this accepted separation rule; domain-specific problem-content APIs and outgoing omission behavior remain design work.

The maintainer subsequently reopened source parts for comparison on 4 November. `getSourceData()` is the new locally implemented metadata
snapshot; `getParts()` has not been implemented. An all-source content view can preserve both good and problematic original content while
normal Email fields remain the successful interpreted view and problems reference the affected source content. Compare that design with
problem-only content retention before choosing the API. Recovery-state flags on normal AttachmentResource values remain rejected.

The maintainer then requested a generic diagnostic-first interface and a complete retention/read plan. The proposed next revision is
specified in [email-source-content-contract-20261104.md](email-source-content-contract-20261104.md). It supersedes the named-header lookup
example in this earlier implementation packet for the next API revision. The current local implementation is not yet changed to those
new source-content interfaces; approval of the precise contract is still under discussion.

1. Complete the combined acceptance review against the exact approved behavior and distinguish implemented import reporting/copy preservation from broader operation-specific diagnostics. The local test/package records do not replace that handoff.
2. Create selective semantic main/website commits, including ADR 0041 and related records under #961, while preserving unrelated staging and dirty content.
3. Publish website before the parent gitlink/main commit to the existing review branches and verify remote heads.
4. Post final supported after-usage and delivered references without verification prose, then close #961 and set its project item Done after accepted delivery.
5. Retire this active plan and register its exact path, reconcile the review records, and handle only the authorized bounded cleanup. The guarded scratch entry and WhatIf are already present; adding them did not authorize deletion.
