# Document proxy bridge-port collisions

- Status: Planned
- Priority: Medium
- Work: Documentation, possible code enhancement

## Gap

Each authenticated-proxy Mailer creates a local bridge on its configured port. Concurrent mailers using the same port can collide, but the guide only says the port can be changed.

## Plan

Explain port ownership and require distinct bridge ports for concurrently active authenticated-proxy mailers. Consider automatic ephemeral-port allocation as a separate enhancement.

## Acceptance criteria

- [ ] Collision conditions are documented.
- [ ] A multi-mailer example uses distinct ports.
- [ ] The loopback-binding fix is complete before this item is closed.
- [ ] Any automatic allocation proposal is tracked separately.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:211-214`
- Bridge creation: `modules/authenticated-socks-module/src/main/java/org/simplejavamail/internal/authenticatedsockssupport/AuthenticatedSocksHelper.java:15-17`
- Binding: `modules/authenticated-socks-module/src/main/java/org/simplejavamail/internal/authenticatedsockssupport/socks5server/AnonymousSocks5ServerImpl.java:45-50`
