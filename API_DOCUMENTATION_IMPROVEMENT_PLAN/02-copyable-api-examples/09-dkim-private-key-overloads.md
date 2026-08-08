# Correct DKIM private-key overloads

- Status: Done
- Priority: Medium
- Work: Documentation

## Problem

The guide showed `dkimPrivateKeyData(File)`, but files are accepted by `dkimPrivateKeyPath(File)`. Data overloads accept `InputStream`, `String`, or `byte[]`.

## Plan

Use a real `byte[]` variable in the in-memory example. Separate path/file inputs from raw-data inputs and document when data is copied, how strings are encoded, and who closes an input stream.

## Acceptance criteria

- [x] Every shown overload exists in 9.1.5.
- [x] File-path and in-memory key examples are not conflated.
- [x] Examples compile in the snippet fixture.

## Evidence

- Documentation: `simplejavamail.org/src/pages/security.hbs:353-393`
- API: `modules/core-module/src/main/java/org/simplejavamail/api/email/config/DkimConfig.java:169-218`
