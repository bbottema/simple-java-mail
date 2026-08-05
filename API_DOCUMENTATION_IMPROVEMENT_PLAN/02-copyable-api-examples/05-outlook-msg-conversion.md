# Correct Outlook MSG conversion calls

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

The examples read binary `.msg` data into a Java `String` and pass it to overloads whose `String` parameter means a filename.

## Plan

Pass the path string directly, or use `File` or `InputStream`. Keep binary data out of text helpers.

## Acceptance criteria

- [ ] All three Outlook conversion examples use the correct overload semantics.
- [ ] No example converts MSG bytes to text.
- [ ] The path, file, and stream alternatives are labeled clearly.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1413-1422`
- API: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/EmailConverter.java:134-153`
