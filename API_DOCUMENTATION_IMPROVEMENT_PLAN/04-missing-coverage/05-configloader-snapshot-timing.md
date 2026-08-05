# Explain ConfigLoader snapshot timing

- Status: Planned
- Priority: Medium
- Work: Documentation

## Gap

The configuration guide does not say that reloading ConfigLoader affects subsequently created builders/mailers rather than live-reconfiguring existing objects.

## Plan

Add a concise timing model and an example that reloads configuration before creating a replacement Mailer.

## Acceptance criteria

- [ ] Existing Mailers are clearly described as unaffected.
- [ ] Builder/object creation timing is explicit.
- [ ] Multi-environment examples create objects after loading their intended configuration.

## Evidence

- Source contract: `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java:334-336,368-370`
