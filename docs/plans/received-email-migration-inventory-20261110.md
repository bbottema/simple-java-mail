# Received Email migration and upkeep inventory

Active phase-0 supplement to the [approved packet](received-email-overhaul-implementation-20261110.md), 10 November 2026. This inventory freezes the relevant released input before the target implementation changes; final target descriptors and artifact validation follow the combined implementation freeze. The lead owns acceptance/publication. No production migration recipe is supplied by this inventory.

## Released source and binary boundary

The input is tag `9.3.10`, commit `ec6cb6ddf258a294800679779b4a09ce8282fe40`, and the published Maven Central `org.simplejavamail:core-module:9.3.10` and `simple-java-mail:9.3.10` JARs. Complete `javap -p -s` output is retained privately under the task's `evidence/released-9.3.10/`. The binary check includes generated accessors rather than assuming source declarations are the complete API. Existing research witnesses for 9.0.0/9.1.0/9.3.6/9.3.9 remain historical per-version evidence; this supplement does not overwrite them or claim every intermediate release was rechecked.

The exact removed application descriptors are below. Owners are `org.simplejavamail.api.email.Email` (E), `org.simplejavamail.api.email.EmailPopulatingBuilder` (B), and `org.simplejavamail.api.email.OriginalSmimeDetails` (D). `List` preserves the generic element `AttachmentResource` in source signatures.

| Owner | Released method | JVM descriptor |
| --- | --- | --- |
| E, B | `getDecryptedAttachments()` | `()Ljava/util/List;` |
| E, B | `getSmimeSignedEmail()` | `()Lorg/simplejavamail/api/email/Email;` |
| E, B | `getOriginalSmimeDetails()` | `()Lorg/simplejavamail/api/email/OriginalSmimeDetails;` |
| B | `notMergingSingleSMIMESignedAttachment()` | `()Lorg/simplejavamail/api/email/EmailPopulatingBuilder;` |
| B | `clearSMIMESignedAttachmentMergingBehavior()` | `()Lorg/simplejavamail/api/email/EmailPopulatingBuilder;` |
| B | `isMergeSingleSMIMESignedAttachment()` | `()Z` |
| D | `getSmimeMode()` | `()Lorg/simplejavamail/api/email/OriginalSmimeDetails$SmimeMode;` |
| D | `getSmimeMime()`, `getSmimeType()`, `getSmimeName()`, `getSmimeProtocol()`, `getSmimeMicalg()`, `getSmimeSignedBy()` | `()Ljava/lang/String;` each |
| D | `getSmimeSignatureValid()` | `()Ljava/lang/Boolean;` |

The released nested `SmimeMode` values are PLAIN, SIGNED, ENCRYPTED and SIGNED_ENCRYPTED. Target-only UNKNOWN/status getters must not be described as released 9.3.10. Target-only `OriginalSmimeDetails.getVerificationStatus()`, `getDecryptionStatus()`, `getFailureReason()`, `getOriginalProtectedMessage()` and their enums disappear with the superseded target type; no invented 9.x getter rewrite follows. `OriginalOpenPgpDetails`, `OpenPgpSignatureResult` and Email/builder `getOriginalOpenPgpDetails()` are also unreleased target surfaces and need only target fixture reconciliation.

`Email.wasMergedWithSmimeSignedMessage:Z` is a released protected field, and the runtime internal `InternalEmailPopulatingBuilder.withSmimeSignedEmail(Email)` is public in bytecode. The protected state and internal bridge are not ordinary supported application configuration; remove obsolete bookkeeping rather than introducing compatibility aliases. The exact internal bridge descriptor is `(Lorg/simplejavamail/api/email/Email;)Lorg/simplejavamail/email/internal/InternalEmailPopulatingBuilder;`. `EmailPopulatingBuilderImpl` supplies the six released B methods above, including Lombok-generated getters. Its public visibility does not make it a separate supported migration target.

## Helpers and continuing descriptors

Published `EmailStartingBuilder` has the following routes, all returning `EmailPopulatingBuilder`:

- `replyingTo(Email)`, `replyingTo(Email, String)` and corresponding `replyingToAll` routes.
- `replyingTo(MimeMessage)`, `replyingTo(MimeMessage, String)` and corresponding `replyingToAll` routes.
- `replyingTo(MimeMessage, boolean, String)`.
- `forwarding(Email)` and `forwarding(MimeMessage)`.

The String is the custom quotation template. Existing useful convenience routes remain; typed operation options do not create a 9.x rename. Static `org.simplejavamail.email.EmailBuilder` also has the above convenience routes except the explicit boolean route. Those older static-to-factory migrations already belong to the existing bootstrap responsibilities, not a new received-message recipe. Preserve existing before witnesses rather than rematching method names broadly.

Published `OutlookMessageData.getBodyHTML()` and `getBodyRTF()` both have `()Ljava/lang/String;`. They are real 9.3.10 read accessors and must be forward-ported. The current 10.0.0 core type is an interface backed by `OutlookMessageDataImpl`; the earlier 9.x public constructors are already classified under the read-result construction migration. Restoring these getters adds no new constructor or optional-provider dependency.

## Semantic classification and manual targets

| Existing responsibility | Actual affected source | Required disposition |
| --- | --- | --- |
| R028 / F27 conversion and resources | Decrypted-list reads, direct resource reads, retained-inner reads and static/configured input routes | Report/source-preserving. Ordinary attachments now describe selected usable payloads. Replacing `getDecryptedAttachments()` with `getAttachments()` is unsafe without knowing which logical file the application consumes. Do not infer an index/name or promote a child's files. |
| R033 / F13 protection inspection | D type/imports, modes, nullable-validity predicates, retained-original assumptions | Report/source-preserving. Manual target selects one actual ValueInspection and explicit application-approved signing keys; no global valid Boolean or inferred signer trust. A verified/full/approved witness must be one signature. Unknown evidence remains indeterminate. |
| R028 / R033 | Retained-inner reads and merge opt-in/opt-out | Report/source-preserving. Read current Email fields, passive child Email or retained original according to actual intent. No automatic replacement with Email itself, attachment 0, or a family summary. |
| R040 / F19 existing advanced configuration | The removed merge setter/reset/getter and any reflective/CLI assumptions | Report/source-preserving under the same received-inspection decision, with existing configuration responsibilities cross-referenced. There is no replacement merge setting or new global property. |
| R044 authored/read/emitted headers | Parsed-header maps, typed/generic replacements, reconstructed output | Existing behavior review: the case-insensitive read map retains useful received facts, while final semantic output follows header roles. Never rewrite authoritative source bytes or infer parsed/composed send policy from provenance. |
| R039 / finalized MIME and R042 / forwarded templates | Interpreted forwards, original forwards, helper-derived confidential reuse and conversion-local consent | Existing behavioral review. New helper/options APIs are target-only. Explain that original forwarding needs complete retained input and interpreted forwarding uses current usable content. Never insert disclosure permission or protection credentials automatically. |

Concrete manual targets are required for three distinct intents: consume a selected opened document, assess that selected document under supplied requirements, and retain/forward original input in its actual EML/MSG format. An application must choose the document and approve keys; a migration cannot guess either. Method references, subclasses, switches, casts, reflection, shared checked-I/O handlers and incomplete attribution remain review sites. No T001-T008 transformation responsibility or new R topic is added.

## Tooling contribution boundary

The current repo has the two inventories in `docs/research/10.0.0-openrewrite-migration-{research,implementation-plan}.md`; there is no recipe module, published recipe/report executable, versioned symbol resource or executable migration corpus here. The implementation plan explicitly assigns those to one independently versioned tooling repository under #766. The available workstation directory inventory supplies no matching tooling checkout. An inventory update alone must not claim an executable migration.

Contribute a bounded, portable fixture packet in this repo for the existing tooling track: versioned released/target symbol manifest, compiling released source, concrete manual target, source-preserving expected report rows/negative controls, and a runner that compiles those separate classpaths against explicitly supplied frozen artifacts. Keep it labeled a fixture contribution, not an OpenRewrite scanner or production recipe. If the actual tooling baseline becomes available, integrate the report cases there rather than inventing a parallel migration engine. #766 remains open with its release obligations.

## Root upkeep ownership

Focused dated amendments belong to ADRs 0006/0008/0013/0031/0035/0036/0037/0040/0041 at their existing paths/anchors. Preserve the concurrent 0037 clarification and all earlier decisions. Reconcile README, unreleased release history, source/conversion references and the comparison companion. The companion receives a design-stage note first and implemented assessment only after lead acceptance; credit a meaningful but moderate overall gain in protected-mail coherence, no SMTP protocol advance or unmeasured market/throughput claim.

No global configuration property, EmailProperty, Spring metadata or lock field is warranted for read-only captured facts, application checking requirements or operation-specific disclosure consent. This conclusion must agree with the final core/helper implementation. Verification evidence stays private/task-local; public documentation describes behavior and decisions only.
