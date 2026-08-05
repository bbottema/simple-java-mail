# Document generated attachment Content-IDs

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

The MIME example shows `Content-ID: <dresscode.txt>` for a normal attachment. Current fallback IDs are opaque `sjm-<UUID>@simplejavamail.generated` values.

## Plan

Show an opaque generated placeholder and separately demonstrate the overload for a caller-supplied stable ID.

## Acceptance criteria

- [ ] Default MIME output uses the current ID shape.
- [ ] Stable-ID behavior is shown only with an explicit ID.
- [ ] Migration notes and feature docs agree.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:861-870`
- Generator: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java:343-355`
- Migration note: `simplejavamail.org/src/pages/migration-notes-9.0.0.hbs:214`
