# Define S/MIME signature verification precisely

- Status: Planned
- Priority: High
- Work: Documentation, possibly API naming follow-up

## Problem

`getSmimeSignatureValid()` is presented as if it authenticates the sender. It verifies cryptographic consistency using the certificate included with the message, but does not establish certificate trust, identity, validity period, revocation status, or a PKIX chain.

## Plan

1. Define exactly what the boolean proves.
2. List the trust checks applications must perform separately.
3. Provide a safe processing sequence: parse, verify signature, validate certificate/trust, then accept identity.
4. Align website text and API Javadocs.

## Acceptance criteria

- [ ] “Valid” is never equated with “trusted sender.”
- [ ] Missing trust, identity, validity, and revocation checks are explicit.
- [ ] Reading and security sections use the same terminology.
- [ ] Any example branches separately on signature consistency and certificate trust.

## Evidence

- Documentation: `simplejavamail.org/src/pages/security.hbs:584-619`
- Verification implementation: `modules/smime-module/src/main/java/org/simplejavamail/internal/smimesupport/SMIMESupport.java:472-511`
