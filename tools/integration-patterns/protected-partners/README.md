# Exchange protected partner documents

A Camel route submits a signed and encrypted message with one required partner document. The application receives the local fixture's actual captured bytes, decrypts/interprets them through SJM, checks that exact document against its approved signing key and protection requirements, then processes or holds it. Complete original input is archived without rebuilding MIME.

Camel owns routing. The application owns partner approval, the required-document contract, persistence and its process-or-hold decision. SJM supplies readable content, captured protection evidence and pure requirements checks. The generated identities are synthetic; accepting one in this application is an explicit local choice, not production PKI or automatic trust in the From address.

## Start here

Run from the matching SJM checkout root with Docker's Linux containers. The build creates synthetic signing/decryption identities; you do not need keys, a host JDK or a real mail account.

```text
docker compose -f tools/integration-patterns/protected-partners/compose.yaml up --build -d --wait
docker compose -f tools/integration-patterns/protected-partners/compose.yaml run --rm scenario
```

Inspect [the submitted jobs](http://localhost:8787/jobs) and [application events](http://localhost:8787/events). A successful received-document decision has `PARTNER_DOCUMENT_PROCESSED` and `PROTECTED_ARCHIVED` events. These events describe the application's document decision and retained input; the job's SMTP receipt remains a separate fact. Partial/unknown submissions remain held.

## Follow one job

Read [`PartnerDocuments`](src/main/java/org/simplejavamail/examples/patterns/PartnerDocuments.java) first. Its Camel route calls [`MailWork.deliverPartnerDocument`](../common/src/main/java/org/simplejavamail/examples/patterns/MailWork.java), then `archiveVerifiedCopy` fetches the fixture's actual MIME. Follow [`PartnerDocumentPolicy.receive`](src/main/java/org/simplejavamail/examples/patterns/PartnerDocumentPolicy.java) to the document selection, requirements checks and storage. A complete original is retained separately from the selected usable document bytes.

The application explicitly approves the configured local partner certificate with `ApprovedSigners`. Its base policy requires one same signature that is verified, fully covers the selected attachment and uses that approved signing key; the selected document must also have been decrypted. Its processing policy deliberately adds `requireAllSignaturesVerified()`. SJM does not universally reject `MIXED`: one full verified approved witness can satisfy the base rule even if another signature is invalid. This application's stricter policy holds that case.

The required-document contract is one attachment named `partner-order.txt`. A missing or duplicate candidate is held rather than guessed. Names alone are not global identities, and the selected occurrence's own inspection is checked. An independently attached child's protection cannot approve the parent's plain document.

`getInspection().getOriginalMessage()` supplies captured input for the archive. `getAttachments()` exposes the document's usable bytes after successful opening. Rebuilding the Email from getters would be an interpreted representation and would not preserve the signed original. Reading a parsed child does not change its parent's attachment payload.

A child's retained original is its own EML/MSG file, without a parent's encryption envelope. An original extracted through parent encryption still needs outgoing protection or explicit conversion permission when forwarded, even if the child encrypts its body while leaving headers clear. The helper uses neutral new carrier metadata for known confidential input; the original getter's metadata and bytes stay unchanged.

## Try a protection decision

Run only the receiving exercises without another SMTP submission:

```text
docker compose -f tools/integration-patterns/protected-partners/compose.yaml run --rm scenario protection
```

[`PartnerDocumentFixtures`](src/main/java/org/simplejavamail/examples/patterns/PartnerDocumentFixtures.java) creates real MIME/S/MIME input. Its scenarios are:

| Scenario | Application decision |
| --- | --- |
| `approved` | Full verified approved signature and decrypted document: process it and retain original input. |
| `unapproved` | Mathematically valid signature from another key: hold the document. |
| `unavailable-verification` | The signature's certificate is unavailable: hold the indeterminate result. |
| `unavailable-decryption` | Receive keys are not supplied: keep the protected original and hold the unavailable required document. |
| `mixed` | One verified approved signature plus another actually invalid signature: base requirements can pass, but this application's strict policy holds it. |
| `outside-coverage` | A plain document sits beside an independent protected child: hold the document; the child does not cover it. |

The local `/partner-documents/scenarios/{scenario}` endpoints drive those exercises. They are trusted synthetic-input teaching endpoints, not an Internet-facing receiving service. Returned DTOs contain the selected-value outcome/reason codes and application action, never serialized Email or key objects. None of these offline receive decisions upgrades SMTP evidence or authorizes a resend.

The DTO keeps actual input-processing Problem codes separate from requirement reasons. A valid but unapproved document can have no processing Problems and still fail the application rule. When the required document cannot be selected, the application reports its own `REQUIRED_DOCUMENT_UNAVAILABLE` reason and leaves the protection-check outcome absent; it does not invent a check over another value.

The response's `documentId` identifies the original under `/data/archive/<documentId>.eml`; accepted documents also have `/data/processed/<documentId>.txt`. It connects the displayed decision with the retained local input. The ordinary Camel job route keeps that correlation under its existing job ID and application events.

## Try an uncertain submission

Run `docker compose -f tools/integration-patterns/protected-partners/compose.yaml run --rm scenario unknown`. A missing final SMTP reply produces held submission evidence. Verification or successful archive work cannot turn that uncertainty into known delivery or permission to resend. The default scenario also retains happy, partial and delayed-feedback controls and then runs all protection decisions.

The two signing identities are generated inside the build and valid for thirty days, with plainly documented demo passwords. Replace the whole local deployment, key-management, trust, access and retention model before considering production use. Holding a document and storing an original do not secure those files by themselves.

The [received-protection guide](https://www.simplejavamail.org/security.html#section-inspecting-received-protection) and [focused partner-document recipe](https://www.simplejavamail.org/email-workload-field-guide.html#check-a-partner-document-before-processing-it) explain native fallback, partial/unknown coverage, key rotation and deliberate reply/forward reuse. Check the value your application will use.

Like the other original framework examples, this is a single application instance. [The shared notes](../README.md) explain resources, licensing and that lifecycle limit. Cleanup removes only this project's synthetic data:

```text
docker compose -f tools/integration-patterns/protected-partners/compose.yaml down --volumes --remove-orphans
```
