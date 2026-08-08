# Correct Outlook MSG conversion calls

- Status: Done
- Priority: Medium
- Work: Documentation and source Javadocs

## Problem

The examples call a nonexistent `readToString(...)` helper, implying that binary `.msg` data should be decoded into a Java `String`, and then pass that
value to overloads whose `String` parameter means a filename.

## Plan

Pass the path string directly, or use `File` or `InputStream`. Keep binary data out of text helpers.

## Acceptance criteria

- [x] All three Outlook conversion examples use the correct overload semantics.
- [x] No example converts MSG bytes to text.
- [x] The path, file, and stream alternatives are labeled clearly.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs` now labels path-string, `File`, and fresh binary `InputStream` alternatives and uses only real APIs.
- API: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/EmailConverter.java` consistently names and documents filename-based `String` parameters.
- Verification: `npm run check`, `npm run build`, `npm run verifyLinks:internal`, targeted `EmailConverterTest`, and generated Javadocs pass.
