# Received Email overhaul: optional protection inventory

Phase-0 source inventory, 10 November 2026. Implements the approved packet's pre-code checkpoint; no production change is represented by this inventory.

## Current paths and boundaries

| Owner | Current path and consequence |
| --- | --- |
| Main MIME import | `ConfiguredEmailConverterImpl.mimeMessageToEmailBuilder` captures `InputInspection`, asks OpenPGP first, asks S/MIME only when OpenPGP did not recognize the root, parses one effective message, then invokes legacy artifact/attachment handling. This cannot interpret repeated cross-family root layers. |
| S/MIME root | `SMIMESupport.processIncoming(Session,MimeMessage,Pkcs12Config)` serializes recognized input and dispatches encrypted/signed branches. `processIncomingEncrypted` additionally opens one signature internally. It returns `SmimePreprocessingResult`, whose details/nested details/artifacts are legacy flattened values. The broad `Exception` recovery currently includes keystore setup defects, contrary to the new approved boundary. |
| S/MIME verification | `verifyIncomingSignature` calls `SmimeCryptoSupport.checkSignature`, which loops signers and reduces to a Boolean. `getSignedByAddress` selects the first certificate CN. Both must be replaced for incoming per-signature facts; CN remains observed metadata and cannot approve a signing key. |
| S/MIME attachments | `decryptAttachments(List,OutlookMessage,Pkcs12Config)` and the MIME overload populate `SmimeParseResultBuilder`; `decryptAttachments` then uses attachment count and generated EML media type to infer a parent body. `processIndependentAttachments` avoids parent updates but does not provide actual selected file metadata, individual facts or child ownership. These legacy receive routes are superseded after main delegates migrate. |
| Exact protected input | `messageBodyPart` uses original serialized body bytes with received Content headers. `effectiveMessage` preserves outer non-content headers and appends clear MIME entity bytes. `retainDetachedSignedContent` deliberately writes the signed part as-is. New receive processing must not canonicalize received signature input or treat a generated EML adapter as original/authored input. |
| OpenPGP root | `OpenPgpSupport.processIncoming` recognizes RFC 3156 `multipart/signed` and `multipart/encrypted`, extracts raw MIME entities through `MimeEntitySupport`, and internally recurses to depth two. `combinedProtection` concatenates signatures but flattens encryption/failure facts. Main must instead control each layer's ordering and shared traversal allowance. |
| OpenPGP signatures | `PgpainlessOpenPgpAdapter.verifyDetachedSignatures` parses every signature packet; `valid`/`rejected` preserve actual `SubkeyIdentifier.getComponentKeyFingerprint()`, algorithms and failures. `readEncryptedMessage` exposes verified/rejected inline signatures only after successful stream close and `metadata.isEncrypted()`. Those facts are reusable; packet presence is not decryption success. |
| OpenPGP failure boundaries | `PassphraseProviderFailure` preserves application callback failures. `signatureProcessingFailure` classifies native exception types, not text. Several current broad catches (`verifyDetached`, key parsing and outer processing) also swallow cancellation/programming defects; new processing must narrow these. Missing matching decryption key is per-input unavailable; malformed configured keys and unusable configured passphrases are setup failures. |
| Key reuse | `FactorySmimeRuntime` owns a lazy runtime per factory, using `SMIMEModule.createRuntime(capacity)`. `SMIMESupport.retrieveSmimeKeyFromPkcs12Keystore` goes through that runtime's `SmimeKeyCache`. No cache/factory/private-key object belongs in Email or inspection snapshots; retain this owner unchanged. |
| Import allowance | `ImportContext.at` shares the same `ImportTraversal`; `newRepresentation` shares allowance with new materialization reporting. Optional providers currently do not receive that scope, so the main one-layer loop must admit each opened layer and child through it rather than a separate provider recursion limit. |

## Minimal replacement internal contract

Add one internal, transient `ProtectionLayerResult` for both modules: recognized/opened flags, current effective MimeMessage, nullable exact opened MimeBodyPart, actual authored-header marker, individual core signature/decryption observations, inspection completeness and recognized input failure information. Module source links are bound by main to the captured occurrence; signatures remain the same observation across field/family views. This is an operation result, not a retained source tree.

`SMIMEModule.openIncomingLayer(Session,MimeMessage,Pkcs12Config)` and `OpenPgpModule.openIncomingLayer(Session,MimeMessage,OpenPgpReceiveConfig)` open at most one protection envelope. Main owns all repeated/cross-family order, component role and traversal scope. An independent file can use a content-only MIME wrapper while its role remains independent; the returned opened entity preserves its actual filename, media type and payload. A child header view requires actual `message/rfc822` content or another grammar-established message, never a synthetic EML filename or attachment count.

Signed extraction keeps readable content even when an individual signature is invalid or unavailable. Signature observations distinguish VERIFIED, INVALID and UNVERIFIED; unknown presence/incomplete examination remains separate. Decryption observations distinguish DECRYPTED, KEY_MISSING/UNAVAILABLE, FAILED and DEFERRED. Public projections/enum spelling are frozen with the core owner's inventory before production edits.

S/MIME verification normalizes the actual certificate public key with the same core routine as ApprovedSigners setup. Capture per-signer certificate identity, public-key identity, signature/digest algorithms and native failure kind; do not retain certificate/provider objects in the evidence. Missing certificate is unavailable verification. Digest mismatch or a returned false verification is INVALID. Setup/key loading occurs outside content recovery. Wrong-recipient and malformed protected bytes are typed input failures. Interrupted work, application callbacks and unrelated runtime defects propagate.

OpenPGP approval uses the full actual signing-key/subkey fingerprint supplied by verified native results; never infer a fingerprint from a 64-bit packet key ID or automatically approve sibling subkeys. Existing real signature packet capture can feed the new core result without changing RFC 3156 scope.

## Early behavioral scenarios

1. Eager actual S/MIME body import plus native HTML fallback and independent protected attached message; root and child facts remain distinct.
2. Independently encrypted PDF opens to the exact original PDF bytes/metadata; encrypted payload remains separately retained. A sole attachment stays independent without a native envelope declaration.
3. Both S/MIME/OpenPGP nesting orders and repeated envelopes advance through one main loop with one budget; signature input bytes stay intact.
4. Two S/MIME signers (valid/invalid or valid/missing-certificate) retain separate results; the approved valid full witness may satisfy the base check and cannot satisfy the strict check.
5. Loaded nonmatching key preserves original and located unavailable observation; wrong keystore password aborts; interruption propagates; readable neighbors survive input failure.
6. OpenPGP valid signature captures actual signing-subkey fingerprint, unavailable key remains UNVERIFIED, and successful packet parsing without encrypted metadata never establishes DECRYPTED.
7. Existing factory-cache capacity/isolation and original protected-byte controls remain unchanged; main admits nested work through shared ImportTraversal.

No new property schema, EmailProperty selector or Spring field is needed for read-only evidence or explicit application key approval. Existing outgoing cryptographic configuration and mandatory encryption behavior keep their current owner.
