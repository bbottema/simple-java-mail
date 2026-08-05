# Correct DKIM private-key overloads

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

The guide shows `dkimPrivateKeyData(File)`, but files are accepted by `dkimPrivateKeyPath(File)`. Data overloads accept `InputStream`, `String`, or `byte[]`.

## Plan

Separate path/file examples from raw-data examples and label the ownership/encoding of each input.

## Acceptance criteria

- [ ] Every shown overload exists in 9.1.5.
- [ ] File-path and in-memory key examples are not conflated.
- [ ] Examples compile in the snippet fixture.

## Evidence

- Documentation: `simplejavamail.org/src/pages/security.hbs:352-370`
- API: `modules/core-module/src/main/java/org/simplejavamail/api/email/config/DkimConfig.java:166-210`
