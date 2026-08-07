# Explain ConfigLoader snapshot timing

- Status: Done
- Priority: Medium
- Work: Documentation

## Gap

The configuration guide does not say that reloading ConfigLoader affects subsequently created builders/mailers rather than live-reconfiguring existing objects.

## Plan

Add a concise timing model and an example that reloads configuration before creating a replacement Mailer.

## Acceptance criteria

- [x] Existing Mailers are clearly described as unaffected.
- [x] Builder/object creation timing is explicit.
- [x] Multi-environment examples create objects after loading their intended configuration.

## Evidence

- `ConfigLoader` Javadocs now describe its process-wide defaults, replacement-object semantics, and the requirement to start fresh builders after a reload for all three `loadProperties(...)` overloads.
- `simplejavamail.org/src/pages/configuration.hbs` now has a dedicated "When changes take effect" section, a replacement-Mailer example, and explicit load-before-build ordering in the multi-environment example.
- Architectural follow-up: [GitHub issue #693](https://github.com/bbottema/simple-java-mail/issues/693) tracks replacing the static loader with instance-based, injectable configuration for milestone `10.0.0`.
- Verification: core-module Javadocs generated successfully; website type/check task, production build, and 1,321-link internal link check passed.
