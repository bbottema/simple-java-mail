# Bind the authenticated SOCKS bridge to loopback

- Status: Done
- Priority: Medium
- Work: Code, tests, documentation

GitHub issue: #676

## Problem

The configuration guide says the bridge port is “always localhost,” but `AnonymousSocks5ServerImpl` binds `new InetSocketAddress(proxyBridgePort)`, which listens on wildcard interfaces. While the bridge is active it accepts unauthenticated SOCKS CONNECT requests and relays them through the authenticated upstream proxy.

## Plan

1. Bind explicitly to a loopback `InetAddress`.
2. Configure Jakarta Mail with that exact loopback literal rather than the ambiguous `localhost` name, so the client and listener use the same IP family.
3. Add a test asserting the listening address is loopback-only.
4. Update the documentation to describe the tested loopback-only binding.
5. Add a concise explanation of why the local bridge exists and when it runs.

## Acceptance criteria

- [x] The bridge cannot be reached through a non-loopback interface.
- [x] The Jakarta Mail SOCKS host matches the address on which the bridge listens.
- [x] Authenticated proxy sends still work.
- [x] Configuration and proxy documentation match the tested binding behavior.
- [x] The change is called out in release notes if it affects unusual custom deployments.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:211-214`
- Implementation: `modules/authenticated-socks-module/src/main/java/org/simplejavamail/internal/authenticatedsockssupport/socks5server/AnonymousSocks5ServerImpl.java:45-50`
- Protocol handler: `modules/authenticated-socks-module/src/main/java/org/simplejavamail/internal/authenticatedsockssupport/socks5server/Socks5Handler.java`
- Tests: `AnonymousSocks5ServerImplTest` proves the bind address is loopback; `MailerSOCKSLiveTest` sends mail through the authenticated-proxy bridge path.
