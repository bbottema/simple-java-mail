# Update serialization transient fields

- Status: Planned
- Priority: Medium
- Work: Documentation, serialization test review

## Problem

The serialization section lists removed `Email.dkimPrivateKeyInputStream` and does not accurately reflect current transient state.

## Plan

Inventory current transient fields from `Email` and `AttachmentResource`, update the list, and explain the practical effect after deserialization.

## Acceptance criteria

- [ ] Removed fields are gone from the docs.
- [ ] Current transient fields are complete for the documented models.
- [ ] A round-trip test anchors the described behavior.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1609-1614`
- Models: `modules/core-module/src/main/java/org/simplejavamail/api/email/Email.java`
- Attachment model: `modules/core-module/src/main/java/org/simplejavamail/api/email/AttachmentResource.java`
