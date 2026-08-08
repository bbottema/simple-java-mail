# Define S/MIME signature verification precisely

- Status: Done
- Priority: High
- Work: Code and documentation

## Problem

`getSmimeSignatureValid()` is presented as if it authenticates the sender. It verifies cryptographic consistency using the certificate included with the message, but does not establish certificate trust, identity, validity period, revocation status, or a PKIX chain.

Three implementation paths also overstate the result: Outlook conversion can infer `true` from successfully extracted content without recording a verification result, a message not classified as definitely signed can return `true` without running the cryptographic check, and combined results currently use OR semantics so one successful signature can hide another failure.

## Plan

1. Make `true` mean that every applicable signature represented by the metadata was actually checked and passed cryptographic verification.
2. Make `false` win when verification results are combined, and leave the value `null` when no check was applicable or performed.
3. List the trust checks applications must perform separately.
4. Preserve lenient parsing from issue #571: invalid signed content remains available when it can be extracted.
5. Align website text and API Javadocs.

## Acceptance criteria

- [x] “Valid” is never equated with “trusted sender.”
- [x] Outlook conversion never infers a valid signature from content extraction alone.
- [x] A `true` result is impossible unless a signer and signed state were found and cryptographic verification ran.
- [x] Combined verification results fail closed.
- [x] Missing trust, identity, validity, and revocation checks are explicit.
- [x] Reading and security sections use the same terminology.
- [x] The documentation does not imply that certificate trust can be evaluated through an API that does not currently expose the signer certificate or chain.

## Evidence

- Documentation: `simplejavamail.org/src/pages/security.hbs:651`
- Verification implementation: `modules/smime-module/src/main/java/org/simplejavamail/internal/smimesupport/SMIMESupport.java:441`
- Regression coverage: `modules/simple-java-mail/src/test/java/org/simplejavamail/internal/smimesupport/ReadSmimeSelfSignedTest.java`
- Result aggregation coverage: `modules/simple-java-mail/src/test/java/org/simplejavamail/internal/smimesupport/OriginalSmimeDetailsImplTest.java`
- Implementation record: [GitHub issue #680](https://github.com/bbottema/simple-java-mail/issues/680)
