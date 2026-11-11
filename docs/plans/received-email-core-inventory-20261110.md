# Received Email core inventory (phase 0, frozen 10 November 2026)

Owned by `inspection_core`; lead reviewed and froze this finite surface before production changes. The lead also approved `ValueInspection.isSignatureInspectionComplete()` so pure checks can distinguish incomplete MIXED evidence without implementation casts. Read alongside the approved implementation packet and design ledger. This is an active coordination document, not a public execution report.

## Source ownership and removals

- `core-module/.../api/email/Email.java` is the actual API-bearing model and currently owns constructor-level `smimeSignedEmail` merging. It already exposes `getPlainText`, `getHTMLText`, `getFromRecipient`, `getToRecipients`, `getCcRecipients`, `getBccRecipients`, `getReplyToRecipients`, `getBounceToRecipient`, `getId`, `getSentDate`, and paired calendar getters. The new inspection interface follows those spellings and does not duplicate values.
- `core-module/.../internal/email/EmailInspection.java` currently assembles source/problem values, snapshots foreign contracts once, and shares its own immutable storage. Keep that factory and use qualified names where the public interface collides; renaming all existing source assembly is unnecessary.
- Existing `EmailInspectionBuilder` is an internal cross-module bridge for `getSourceData`, `getProblems`, and `retainInspection`. Extend/coordinate it with the inbound owner for current-value evidence; do not add application setters.
- Existing `OriginalSmimeDetails` has flattened nullable validity/metadata and default statuses; it is a released removal, not the target internal model. `OriginalOpenPgpDetails`, `OpenPgpSignatureResult`, `OpenPgpDetailsImpl`, and `OpenPgpSignatureResultImpl` are unreleased target surfaces with existing per-occurrence facts; migrate useful facts into the shared observation model without claiming 9.x edits.
- Existing `EmailSourceElement` supplies `EmailSourceIdentity getOrigin()` and `String getLocation()`. New result source lists point to those existing captured occurrences; no second source tree or location parser is needed.
- `PublicApiBoundaryTest` and `src/test/core-only/.../CoreOnlyConsumer.java` own core-only and no-construction checks. `EmailSourceContent` already owns repeatable captured bytes and complete/prefix distinction. Do not infer originals from source representation alone or re-read an arbitrary DataSource.

## Proposed supported signatures

All read interfaces below extend `Serializable`; all returned lists are immutable, never null; nullable members are stated explicitly. Library-produced interfaces have no static factory, constructor, public builder or mutation. Enum types are top-level so the existing read-contract test can continue rejecting declared construction classes.

| Type | Exact supported members |
| --- | --- |
| `EmailInspection` | `ValueInspection getPlainText()`, `getHTMLText()`, `getSubject()`, `getFromRecipient()`, `getToRecipients()`, `getCcRecipients()`, `getBccRecipients()`, `getReplyToRecipients()`, `getBounceToRecipient()`, `getId()`, `getSentDate()`, `getCalendar()`; `ValueInspection getAttachment(int index)`, `getEmbeddedImage(int index)`; `ProtectionInspection getSmime()`, `getOpenPgp()`; nullable `AttachmentResource getOriginalMessage()`, `getOriginalAttachment(int index)` |
| `ValueInspection` | `ValueOrigin getOrigin()`; `ValueDerivation getDerivation()`; `ValueAvailability getAvailability()`; `List<EmailSourceElement> getSources()`; `SignatureStatus getSignatureStatus()`; `EncryptionStatus getEncryptionStatus()`; `List<SignatureInspection> getSignatures()`; `List<DecryptionInspection> getDecryptions()` |
| `SignatureInspection` | `SignatureObservation getObservation()`; `ProtectionCoverage getCoverage()` |
| `SignatureObservation` | `UUID getReference()`; `ProtectionFamily getFamily()`; `SignatureVerification getStatus()`; nullable `String getSignerName()`, `getSignerKeyId()`, `getSigningKeyFingerprint()`, `getSignatureAlgorithm()`, `getHashAlgorithm()`, `getFailureReason()`; `List<EmailSourceElement> getSources()` |
| `DecryptionInspection` | `UUID getReference()`; `ProtectionFamily getFamily()`; `DecryptionStatus getStatus()`; `ProtectionCoverage getCoverage()`; nullable `String getEncryptionAlgorithm()`, `getFailureReason()`; `List<EmailSourceElement> getSources()` |
| `ProtectionInspection` | `ProtectionFamily getFamily()`; `boolean isInspectionComplete()`; `List<SignatureObservation> getSignatures()`; `List<DecryptionInspection> getDecryptions()`; `List<EmailSourceElement> getSources()` |
| `ProtectionCheck` | `ProtectionOutcome getOutcome()`; `boolean isSatisfied()`; `List<ProtectionReason> getReasons()` |
| `ProtectionReason` | `ProtectionReasonCode getCode()`; `String getMessage()`; `List<EmailSourceElement> getSources()` |

Family inspection identifies processing of this logical message and its own selected layers; it is not a whole-Email verified Boolean. Independent child observations stay in the child. `SignatureInspection` wraps the same immutable observation referenced in family results; only its selected-value coverage is contextual. Decryption attempt coverage is likewise scoped to the current value when listed there; family lists describe the established layer scope through their source links and do not imply every selected field is decrypted.

Lead clarification during implementation on 10 November: historical family scope includes this source's directly opened attachment-payload layers, including after current occurrence clearing/replacement. The corresponding current value reuses that observation/reference while its association is retained; independent child Email internal body layers remain child-owned and excluded. Family processing is descriptive history, never a current whole-Email success/requirements verdict. No extra root/child crypto pass or public method is introduced.

| Enum | Members and meaning |
| --- | --- |
| `ValueOrigin` | `RECEIVED`, `EXTRACTED`, `DERIVED`, `CALLER_SUPPLIED`, `UNKNOWN` |
| `ValueDerivation` | `NONE`, `NATIVE_HTML`, `RTF_ENCAPSULATED_HTML`, `RTF_ENCAPSULATED_TEXT`, `RTF_RENDERED_HTML`, `UNKNOWN`; extraction/decryption itself is expressed by origin and protection, without an exhaustive transformation language |
| `ValueAvailability` | `PRESENT` (including empty), `ABSENT` (visited and established absent), `UNAVAILABLE` (selected processing could not expose it), `UNINSPECTED` (deliberately deferred), `UNKNOWN` (uncaptured history, including old snapshots) |
| `SignatureStatus` | `VERIFIED`, `INVALID`, `UNVERIFIED`, `UNSIGNED`, `MIXED`, `UNKNOWN` |
| `EncryptionStatus` | `NOT_ENCRYPTED`, `DECRYPTED`, `ENCRYPTED`, `UNKNOWN` |
| `ProtectionCoverage` | `FULL`, `PARTIAL`, `UNKNOWN`; a signature observation only appears when associated, so no `NONE` entry is needed |
| `ProtectionFamily` | `SMIME`, `OPENPGP` |
| `SignatureVerification` | `VERIFIED`, `INVALID`, `UNVERIFIED`; known signature with unavailable/skipped verification is UNVERIFIED, not mathematical invalidity |
| `DecryptionStatus` | `DECRYPTED`, `DEFERRED`, `UNAVAILABLE`, `FAILED`; attempts represent known encrypted input, so not-encrypted belongs to the field summary rather than a fabricated attempt |
| `ProtectionOutcome` | `SATISFIED`, `NOT_SATISFIED`, `INDETERMINATE` |
| `ProtectionReasonCode` | `VALUE_ABSENT`, `EVIDENCE_UNAVAILABLE`, `SIGNATURE_REQUIRED`, `SIGNER_NOT_APPROVED`, `SIGNATURE_INVALID`, `SIGNATURE_UNVERIFIED`, `COVERAGE_INCOMPLETE`, `DECRYPTION_REQUIRED`, `DECRYPTION_UNAVAILABLE`, `STRICT_SIGNATURE_INVALID`, `STRICT_SIGNATURE_UNVERIFIED` |

No result holds a certificate, provider key, parser, Email ancestor, callback, private key, or Throwable. Failure text is explanatory, never a classifier.

Source-grounded supplement, accepted by the lead during implementation on 10 November: `DecryptionInspection` additionally exposes immutable `List<String> getRecipientKeyIds()`. The old unreleased OpenPGP inspection/tests already retained actual encryption-recipient IDs; preserve that diagnostic fact without restoring flat result APIs. `DecryptionInspectionImpl.builder().recipientKeyIds(...)` snapshots the observed strings; unavailable/uncaptured IDs are an empty list. This performs no input/crypto work and grants no signer/recipient approval. It belongs to target-only fixtures, not an invented released-9.x migration.

## Application-owned configuration

`ApprovedSigners` is a final immutable serializable value with `builder()`, `toBuilder()`, `Set<String> getSmimeSigningKeyFingerprints()`, and `Set<String> getOpenPgpSigningKeyFingerprints()`. Its explicit `Builder` has:

```java
Builder addSmimeCertificate(X509Certificate certificate);
Builder addOpenPgpSigningKeyFingerprint(String fingerprint);
Set<String> getSmimeSigningKeyFingerprints();
Set<String> getOpenPgpSigningKeyFingerprints();
Builder clearSmimeCertificates();
Builder clearOpenPgpSigningKeyFingerprints();
ApprovedSigners build();
```

The S/MIME setter snapshots a normalized public-key identity immediately (no certificate object is retained); getters perform no key access. Use canonical RSA/DSA/EC public-key mathematical components where JCA exposes them, preserving algorithm/domain parameters, with a canonical JCA/SPKI fallback for other supported public keys. Use the exact same internal normalizer for captured signatures. SHA-256 labels identify those normalized signing keys. Same-key certificate renewal/provider representation can match; another key requires explicit approval. OpenPGP accepts full 40- or 64-hex signing-key fingerprints, normalized for spelling only. No short IDs, implicit subkey expansion, keyring parser or trust loading. Setters reject null before mutation; OpenPGP text shape and an empty final approved set are validated at `build()` while builder getters can inspect unfinished input. Removing one family preserves the other.

`ProtectionRequirements` is a final immutable serializable value with `builder()`, `toBuilder()`, nullable `ApprovedSigners getApprovedSigners()`, `boolean isDecryptedContentRequired()`, `boolean isAllSignaturesVerifiedRequired()`, and `ProtectionCheck check(ValueInspection value)`. Its explicit `Builder` has:

```java
Builder requireVerifiedSignatureFrom(ApprovedSigners approvedSigners);
Builder requireDecryptedContent();
Builder requireAllSignaturesVerified();
ApprovedSigners getApprovedSigners(); // nullable when no signer requirement supplied
boolean isDecryptedContentRequired();
boolean isAllSignaturesVerifiedRequired();
Builder clearVerifiedSignatureRequirement();
Builder clearDecryptedContentRequirement();
Builder clearAllSignaturesVerifiedRequirement();
ProtectionRequirements build();
```

`requireVerifiedSignatureFrom` replaces the supplied immutable expectation, rejects null before mutation, and does not perform cryptographic work. Each clear affects only its own choice. Builders inspect unfinished combinations without calling build. Construction rejects no requirements and strict-without-approved-signer, avoiding accidental vacuous acceptance. A decryption-only policy is valid. Read/check APIs and explicit operation consent introduce no EmailProperty, factory property/lock or Spring schema; templates cannot manufacture facts or permission.

## Finite projection

| Evidence for current value | Signature summary |
| --- | --- |
| Fully visited, no associated signatures, no unknown coverage | `UNSIGNED` |
| No captured history, deferred/unopened scope or associated coverage UNKNOWN | `UNKNOWN` (unless known outcomes already establish MIXED) |
| One or more FULL signatures, all VERIFIED, complete contributing scope | `VERIFIED` |
| All known FULL signatures INVALID, complete contributing scope | `INVALID` |
| All known FULL signatures UNVERIFIED, complete contributing scope | `UNVERIFIED` |
| Different individual results on full scope, or known covered plus uncovered contributions (`PARTIAL`) | `MIXED`; retain every contextual result |

Aggregate status never upgrades partial coverage to FULL. A native origin does not imply unsigned; an enclosing layer may cover native material. An invalid signature does not remove readable content.

| Evidence for current value | Encryption summary |
| --- | --- |
| Established absence of covering encryption | `NOT_ENCRYPTED` |
| Established FULL recovered encrypted scope over all contributions | `DECRYPTED` |
| Selected payload itself is known unopened ciphertext | `ENCRYPTED` |
| Partial/unknown association, unopened containing scope with no selected recovered content, deferred or uncaptured scope | `UNKNOWN` |

Repeated successfully opened layers can yield DECRYPTED only when all selected contributions are established as recovered; an encrypted sibling is irrelevant. A failed inner attempt does not erase an actually decrypted outer contribution; where selected content coverage remains unresolved, UNKNOWN is conservative.

Implementation-review clarification accepted by the lead on 10 November: the actual `simple-java-mail/.../converter/internal/mimemessage/ProtectionScope.combine` may establish full recovery collectively across independently encrypted contributing fragments while retaining each distinct attempt as PARTIAL for the combined value. `requireDecryptedContent()` evaluates that authoritative DECRYPTED summary; it does not demand one common encryption or fabricate a joint attempt. The same-witness rule remains specific to approved signatures. Mixed decrypted/plain contributions retain UNKNOWN encryption coverage.

## Check truth table

1. `ABSENT` yields NOT_SATISFIED / VALUE_ABSENT; PRESENT empty is evaluated as present. UNAVAILABLE/UNINSPECTED/UNKNOWN availability yields INDETERMINATE unless another required condition is positively unmet. The check never reads or mutates Email/Problems.
2. Approved-signature satisfaction requires one *same* observation with VERIFIED, FULL and an approved actual signing-key fingerprint. MIXED can pass this base rule. A verified signature from another key does not combine with an invalid approved signature.
3. Complete evidence with no possible qualifying candidate is NOT_SATISFIED. Known unsigned, all invalid, only definitely unapproved keys, or only known PARTIAL candidates supply stable reasons. Unverified approved/unknown-key candidates, UNKNOWN coverage, or incomplete inspection that could hide a qualifying witness yield INDETERMINATE without a witness.
4. Decryption requirement passes only the authoritative DECRYPTED summary, which establishes that every selected contribution was recovered. Coverage can be collective: two contributions opened under different encryptions have individually PARTIAL contextual attempts while their combined selected value is DECRYPTED. Do not invent a common attempt or apply the signature same-witness rule to decryption. A decrypted/plain mixture remains UNKNOWN. NOT_ENCRYPTED and known unopened ENCRYPTED are NOT_SATISFIED. UNKNOWN is INDETERMINATE. Reasons distinguish no required decryption from unavailable work.
5. Strict signatures additionally require every relevant associated occurrence VERIFIED and complete inspection. Any known INVALID yields NOT_SATISFIED; any UNVERIFIED/UNKNOWN relevant result yields INDETERMINATE. It never requires all signers approved or extends to independent children.
6. Required conditions combine by evidence: any positively unmet condition gives NOT_SATISFIED even if another is indeterminate; otherwise uncertainty gives INDETERMINATE; otherwise SATISFIED. Successful checks have an empty reasons list.

## Internal assembly boundary for other owners

Implement a separate internal `ReceivedEmailInspection` immutable snapshot plus Lombok builder in core, a `ValueInspectionImpl` builder with explicit completeness flags, and immutable observation/coverage/family/reason implementations. The public `isSignatureInspectionComplete()` fact supports pure checks. Derive the signature summary from observations and completion on every new snapshot rather than exposing an internal summary setter that can retain a stale claim across `toBuilder()`. Internal utility/factory methods snapshot foreign read implementations and share owned ones. Main inbound assembly sets named fields and indexed occurrence lists directly; copy/edits replace only affected value entries while preserving historical family/original data. It must not map occurrences by resource equality, name, hashing or content access.

Use internal `SigningKeyFingerprint.forSmime(PublicKey)` and `forOpenPgp(String)` from optional capture and ApprovedSigners setup. Return only core-owned text identifiers. Other modules consume the exact same normalizer rather than computing certificate hashes or inventing separate fingerprint formats.

Focused checks will cover the same-witness trap, known partial/unknown coverage, strict mixed failures, absent versus empty, no-requirement combinations, renewed-key/provider normalization, short PGP IDs, immutable builder snapshots, serialization, core-only compilation and negative public construction. Integration owners prove real capture/routing/child isolation and final MIME.

## Selective equality and shared header roles (10 November implementation review)

`EqualsHelper` retains its existing typed ID/date/Bounce-To comparisons and order-insensitive occurrence matching for visible/Reply-To/override recipients and resources. The rich `getHeaders()` projection must not reimpose recipient order, duplicate typed authority, or add MIME/trace evidence to equality. A single core-internal `HeaderRoles` classification is shared with main's `OutgoingHeaderRoles` output decision; no public selector or second divergent role table is introduced.

| Role | Equality | Reconstructed output |
| --- | --- | --- |
| Typed authored/notification fields | Existing typed comparands; omit duplicate read-header projection, including unavailable raw fallback | Existing typed producer paths; raw fallback is readable only |
| MIME `Content-*` structure/encoding and MIME-Version | Omit source/header structure; existing typed body/resource encoding comparisons remain | Producer-owned; incompatible explicit override fails with existing guidance |
| Delivery/authentication history and obsolete signatures | Omit raw history; typed Bounce-To remains comparable | No automatic current claim; explicit incompatible claim fails |
| Sender/Comments/Keywords | Compare selected semantic additional values | Existing explicit-authoring decision |
| Custom fields, threading and standalone name/filename/size | Compare case-insensitive names and ordered value occurrences | Ordinary additional field handling; standalone tokens do not override resource metadata |
| Parser placeholder `:` | Omit | No emitted placeholder; explicit invalid choice fails |

Intentional target consequences are removal of raw delivery/authentication/obsolete-signature comparands (including previously retained invalid Return-Path text) and the old flattened incoming-detail comparands, without adding their replacement evidence graphs/original bytes. Current typed values/signing choices still compare as before. Real imported recipient-order, raw trace/structure, invalid date/return-path and typed ID/date/Bounce-To controls accompany custom/threading/standalone-name controls and existing borrowed-resource no-read regressions. Existing generic literal `name` assertions remain valid.
