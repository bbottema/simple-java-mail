# Document generated attachment Content-IDs

- Status: Completed
- Priority: Medium
- Work: Documentation

## Problem

The MIME example shows `Content-ID: <dresscode.txt>` for a normal attachment. Current fallback IDs are opaque `sjm-<UUID>@simplejavamail.generated` values.

## Plan

Show an opaque generated placeholder and separately demonstrate the overload for a caller-supplied stable ID.

## Acceptance criteria

- [x] Default MIME output uses the current ID shape.
- [x] Stable-ID behavior is shown only with an explicit ID.
- [x] Migration notes and feature docs agree.

## Resolution

- Replaced the filename-shaped fallback in the attachment MIME example with a representative `sjm-<UUID>@simplejavamail.generated` value.
- Explained that a new opaque attachment ID is generated when the MimeMessage is produced unless the caller supplies one.
- Clarified that embedded images retain their resource-name fallback so HTML `cid:` references continue to work.
- Kept stable-ID behavior tied to the explicit Content-ID overload.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:890-901`
- Generator: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java:343-355`
- Migration note: `simplejavamail.org/src/pages/migration-notes-9.0.0.hbs:215-224`
