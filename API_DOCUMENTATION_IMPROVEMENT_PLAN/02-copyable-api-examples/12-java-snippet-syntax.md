# Repair remaining Java snippet syntax

- Status: Done
- Priority: Medium
- Work: Documentation

## Problem

Several examples contain syntax errors: ellipses attached to recipient variable names, an extra closing parenthesis, a missing semicolon, and `printStrackTrace()`. Some transport examples also configure several alternatives on one builder, even though each call replaces the previous choice.

## Plan

1. Repair each known syntax defect.
2. Present transport strategies as alternatives instead of consecutive configuration calls.
3. Verify the previously reported TLS example and leave it alone if the earlier TLS work already repaired it.
4. Retain `.(..)` as the established shorthand for omitted builder configuration.

## Acceptance criteria

- [x] Known syntax defects are removed.
- [x] The transport-strategy overview presents alternatives, not one overwriting chain.
- [x] Nearby examples have been checked for the same concrete mistakes.

## Evidence

- `simplejavamail.org/src/pages/features.hbs`: repaired recipient, content-transfer-encoding, attachment, calendar, and exception-handling examples; split mutually exclusive encoding, SMTP, and transport choices into explicit alternatives.
- `simplejavamail.org/src/pages/debugging.hbs`: repaired the override-recipient example.
- `simplejavamail.org/src/pages/security.hbs`: presents transport strategies as alternatives and separates the Java and properties forms of opportunistic TLS configuration.
- The certificate-trust example reported by the audit was already valid after the earlier TLS documentation work and was left unchanged.
- The established `.(..)` notation remains in place where it deliberately means omitted builder configuration.
- Verification: `npm run check` and `npm run verifyLinks` passed on 6 August 2026.
