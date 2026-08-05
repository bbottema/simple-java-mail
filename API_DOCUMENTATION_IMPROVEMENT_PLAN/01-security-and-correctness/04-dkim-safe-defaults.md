# Replace unsafe DKIM examples

- Status: Planned
- Priority: High
- Work: Documentation

## Problem

Primary DKIM examples enable the body-length parameter and exclude `From` and `Subject`. The API warns that `l=` permits unsigned appended content; excluding `From` is incompatible with a valid DKIM signature and excluding `Subject` weakens integrity.

## Plan

1. Use safe defaults in the first complete example.
2. Remove `From` and `Subject` from header-exclusion examples.
3. Put `useLengthParam(true)` in an advanced warning block, if retained at all.
4. Explain what header exclusions are for and which headers should remain signed.

## Acceptance criteria

- [ ] The normal example keeps `useLengthParam(false)`.
- [ ] `From` is always signed in examples.
- [ ] Security consequences precede any advanced opt-in example.
- [ ] Property and programmatic examples agree.

## Evidence

- Documentation: `simplejavamail.org/src/pages/security.hbs:352-385`
- API warning: `modules/core-module/src/main/java/org/simplejavamail/api/email/config/DkimConfig.java:60-69`
- Signing implementation: `modules/dkim-module/src/main/java/org/simplejavamail/internal/dkimsupport/DKIMSigner.java:43-49`
