# Repair remaining Java snippet syntax

- Status: Planned
- Priority: Medium
- Work: Documentation, build tooling

## Problem

Several examples contain Java-looking placeholders or syntax errors: `yourRecipient2...`, an extra closing parenthesis, `testGroupInboxRecipient...`, a missing semicolon, `printStrackTrace()`, a broken TLS trust chain, and four transport strategies chained as though all remain active.

## Plan

1. Repair each known snippet.
2. Mark intentional pseudocode explicitly.
3. Extract compilable Java blocks into a fixture compiled during site checks.

## Acceptance criteria

- [ ] Known syntax defects are removed.
- [ ] The transport-strategy overview presents alternatives, not one overwriting chain.
- [ ] Pseudocode is visually and mechanically distinguishable.
- [ ] CI compiles every example marked as Java.

## Evidence

- `simplejavamail.org/src/pages/features.hbs:275,510,842,1675-1680`
- `simplejavamail.org/src/pages/debugging.hbs:66`
- `simplejavamail.org/src/pages/security.hbs:79-85,260-265`
