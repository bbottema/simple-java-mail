# Document proxy bridge-port collisions

- Status: Done
- Priority: Medium
- Work: Documentation, possible code enhancement

## Gap

Each authenticated-proxy Mailer creates a local bridge on its configured port. Concurrent mailers using the same port can collide, but the guide only says the port can be changed.

## Plan

Explain port ownership and require distinct bridge ports for concurrently active authenticated-proxy mailers. Consider automatic ephemeral-port allocation as a separate enhancement.

## Acceptance criteria

- [x] Collision conditions are documented.
- [x] A multi-mailer example uses distinct ports.
- [x] The loopback-binding fix is complete before this item is closed.
- [x] Any automatic allocation proposal is tracked separately.

## Evidence

- `MailerGenericBuilder.withProxyBridgePort(...)` now explains loopback binding, per-Mailer bridge ownership, sharing within one Mailer, collisions between separate Mailers, and the anonymous-proxy exception.
- `simplejavamail.org/src/pages/configuration.hbs` now has a dedicated **Authenticated proxy bridge ports** section with a two-Mailer example using ports 1081 and 1082. The proxy capability links directly to it.
- The loopback-only bridge fix is complete in [#676](https://github.com/bbottema/simple-java-mail/issues/676).
- Automatic port allocation is tracked separately in [#694](https://github.com/bbottema/simple-java-mail/issues/694), including the session-update work required before port `0` can be supported.
- Verification: core-module Javadocs passed; website type/check task, production build, and 1,325-link internal link check passed.
