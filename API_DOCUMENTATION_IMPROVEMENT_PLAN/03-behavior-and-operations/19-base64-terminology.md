# Remove Base64 security terminology

- Status: Done
- Priority: Low
- Work: Documentation

## Problem

Base64 content-transfer encoding is called an extra layer of obfuscation. That can be read as a security or confidentiality property, which Base64 does not provide.

## Plan

Describe Base64 in terms of binary safety and transport compatibility. Explicitly state that it is encoding, not encryption or protection.

## Acceptance criteria

- [x] “Obfuscation” is removed from the content-encoding section.
- [x] The explanation focuses on transport characteristics.
- [x] No encoding is presented as a security mechanism.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:565-568`
