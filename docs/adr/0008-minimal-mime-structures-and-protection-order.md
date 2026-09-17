# ADR 0008: Build minimal MIME structures, then protect the finalized content

- Status: Accepted (retrospective record of implemented design)
- Decision recorded: 2026-09-16; this is not the original decision date
- Applies to: Composed messages and inbound conversion, including unreleased 10.0.0 protection processing
- Implementation status: Implemented; S/MIME and OpenPGP are alternative outbound protection families

## Context

A universally nested `mixed` / `related` / `alternative` message can be valid but still confuse email clients when layers contain no corresponding feature. [#144](https://github.com/bbottema/simple-java-mail/issues/144) records Outlook 365 showing an attachment icon simply because `multipart/mixed` existed, even without attachments. Separately, callers expect Email getters to return their supplied values, and signatures require stable MIME bytes once encoding and structure have been determined.

## Decision

Keep the Email model separate from wire encoding. Choose the least complex MIME producer that exactly matches three content dimensions: attachments/forwarding require mixed content, embedded images require related content, and multiple plain/HTML/calendar bodies require alternatives. Eight producers cover their combinations. Security processing wraps the resulting MIME entity; it does not create a second set of body producers for each security combination.

Finalize the ordinary MIME entity, then apply **either S/MIME or OpenPGP/MIME**, signing before encryption within that family, and apply DKIM last. The builder rejects simultaneous outbound S/MIME and OpenPGP configuration. The sequential checks inside the producer are not an API promise to support both families on one composed message.

On input, recognize protection and verify/decrypt the original representation before passing effective clear content to the general MIME parser. Preserve original protected bytes and expose result metadata independently of readable content. Cryptographic signature validity is not an application trust decision; key discovery and trust remain application-owned. Do not interpret an OpenPGP `multipart/signed` as S/MIME merely because its container is signed.

## MIME producer selection

The selector's three booleans map to these producers. Mixed content means attachments or a forwarded email; related content means embedded images; alternative content means more than one body variant among plain text, HTML, and calendar text.

| Producer | Mixed | Related | Alternative |
| --- | --- | --- | --- |
| `MimeMessageProducerSimple` | no | no | no |
| `MimeMessageProducerAlternative` | no | no | yes |
| `MimeMessageProducerRelated` | no | yes | no |
| `MimeMessageProducerMixed` | yes | no | no |
| `MimeMessageProducerMixedRelated` | yes | yes | no |
| `MimeMessageProducerMixedAlternative` | yes | no | yes |
| `MimeMessageProducerRelatedAlternative` | no | yes | yes |
| `MimeMessageProducerMixedRelatedAlternative` | yes | yes | yes |

The base producer uses `MessageIdFixingMimeMessage` so a caller-supplied Message-ID survives later wrapping. S/MIME encryption also considers recipient-level certificates when deciding whether the module is needed; an absent global certificate does not mean no recipient requested encryption.

## Rationale and evidence

- [#144](https://github.com/bbottema/simple-java-mail/issues/144) explicitly chooses structure based on actual content to resolve #133's client behavior. [fc714cfe](https://github.com/bbottema/simple-java-mail/commit/fc714cfe4937c80660b3a1ebed54cc01bf002fc7) introduced the dynamic producers, and the [completion comment](https://github.com/bbottema/simple-java-mail/issues/144#issuecomment-394168654) confirms coverage of all combinations.
- [#232](https://github.com/bbottema/simple-java-mail/issues/232) explains why encoding attachment names during building made getters surprising and incoming Outlook conversion awkward. [ccd2ef12](https://github.com/bbottema/simple-java-mail/commit/ccd2ef120e1877fa23a6c2a574fdbfe71a4c3d2d) deliberately moved MIME encoding to the sending phase to keep the Email model clean.
- [8bf8043b](https://github.com/bbottema/simple-java-mail/commit/8bf8043bd21f8ac7f57ad19b962f26610bf10adc) repaired S/MIME/DKIM and envelope-sender interactions. The [maintainer's #297 comment](https://github.com/bbottema/simple-java-mail/issues/297#issuecomment-1440042345) confirms the established S/MIME signing, encryption, then DKIM order.
- [#704](https://github.com/bbottema/simple-java-mail/issues/704) requires exact signed bytes, final encodings/boundaries, explicit mixed-family behavior, and distinct missing-key/invalid-signature states. The [completed pipeline plan](https://github.com/bbottema/simple-java-mail/blob/127b2128a38a592f166b6ca451ef33f283963185/MIME_PIPELINE_IMPROVEMENT_PLAN/README.md) selects alternative protection families and pre-parse protection handling; [883814aa](https://github.com/bbottema/simple-java-mail/commit/883814aadcf65252f3163d3a04ed4846f2a98dd6) implements them.

Keeping DKIM last follows the requirement that its body hash cover the outgoing protected representation; mutating MIME afterward risks invalidation. This explains the documented order, rather than inventing a separate historical debate about every permutation.

### Delegate OpenPGP cryptographic orchestration

[af7c94b8](https://github.com/bbottema/simple-java-mail/commit/af7c94b8e01cad5ba2e019650e9d992cea20a56b), 2026-09-06, replaces direct low-level Bouncy Castle signing, encryption, key selection, verification, and decryption orchestration with a [PGPainless adapter](../../modules/openpgp-module/src/main/java/org/simplejavamail/internal/openpgpsupport/PgpainlessOpenPgpAdapter.java). Simple Java Mail still owns MIME framing, exact entity selection, nesting limits, configuration translation, and result states. Bouncy Castle remains underneath and in internal key/signature handling; the change does not remove it entirely or expose PGPainless in the public configuration API.

This narrows the library's responsibility from maintaining the cryptographic workflow to adapting a specialist implementation. That maintenance rationale is inferred from the removed implementation and the new boundary: the commit states the delegation but provides no comparative explanation of why PGPainless was selected over other libraries. The accompanying tests explicitly cover expired-key rejection, signing-key capabilities, selected subkeys, ciphertext integrity, configured algorithms, and concurrent use.

The tradeoff is an additional optional dependency and reliance on its key-validation and verification policy. Upgrades therefore need compatibility and interoperability checks, not only MIME round trips. Applications still own identity trust; a valid result under the cryptographic policy is not proof that a key belongs to a claimed sender.

## Alternatives

**One universal multipart structure** was the prior implementation and was replaced for demonstrated client compatibility. **Encoding values in builders** was also replaced in response to #232. **Arbitrary outbound nesting of S/MIME and OpenPGP** was explicitly excluded from the first OpenPGP release by the plan. **Parsing and reserializing before verification** would discard the original byte boundary and is incompatible with the documented protection requirements.

## Consequences

Simple messages stay simple and clients see multipart containers that correspond to real content. Adding another body concept requires revisiting selector dimensions and affected producers. Encoding belongs at conversion; resource names, content IDs, and attachment classification still have separate compatibility rules documented in the [resource naming history](../../MIME_RESOURCE_NAMING_REPORT.md).

Security and conversion must preserve Message-ID and exact protected bytes across repeated serialization. Missing optional modules must not silently downgrade requested outbound protection. On incoming mail, accessible content and unavailable verification/decryption facts remain distinct; successful parsing alone is not evidence of trust. Exact EML bypasses this composition pipeline and uses its own preservation contract.

## Implementation anchors

[MimeMessageProducerHelper](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageProducerHelper.java), [SpecializedMimeMessageProducer](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java), [builder validation](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl.java), and [EmailConverter](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/EmailConverter.java) implement these boundaries. [OpenPgpMimeTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/openpgp/OpenPgpMimeTest.java) and [OpenSSL S/MIME interoperability tests](../../modules/simple-java-mail/src/test/java/org/simplejavamail/internal/smimesupport/OpenSslSmimeInteroperabilityTest.java) are existing verification anchors, not newly executed tests for this record.
