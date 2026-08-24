# Step 6: Implement Unix-domain sockets with a loopback fallback

- Status: In progress
- Depends on: Steps 2 and 5
- Primary module: `cli-module`
- Primary Java APIs: `UnixDomainSocketAddress`, `StandardProtocolFamily.UNIX`, `ServerSocketChannel`, `SocketChannel`, `Selector`

## Goal

Provide one transport abstraction whose preferred implementation uses Java 17 Unix-domain sockets across supported Windows and Unix-like systems and whose fallback is authenticated loopback TCP.

## Tests first

1. Run the complete Step 5 protocol suite over a real Unix-domain server and client channel.
2. Force an unsupported `StandardProtocolFamily.UNIX` result and run the same suite over loopback TCP.
3. Force socket-path-too-long, access-denied, stale-socket, address-in-use, read-only-filesystem, and unsupported-filesystem failures and classify which ones may fall back.
4. Prove authentication or private-state failures never trigger a less secure fallback.
5. Assert the TCP implementation binds an explicit loopback address and an ephemeral port, never wildcard IPv4 or IPv6.
6. Inspect the published discovery record and prove it identifies only the selected local transport.
7. Connect multiple clients concurrently and exercise fragmented frames, slow clients, early disconnects, and timeouts.
8. Stop and crash the Unix-domain server on Windows, Linux, and macOS and verify socket-file cleanup and safe stale recovery.
9. Test a supported modern Windows build and a forced unsupported-Windows capability path.
10. Test an unusual or mocked Unix provider that throws `UnsupportedOperationException` despite Java 17.

## Implementation

1. Define internal `LocalServerTransport` and `LocalClientTransport` boundaries that expose byte channels and endpoint metadata, not Mailer or CLI types.
2. Prefer `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` with a deterministic short `UnixDomainSocketAddress` under the validated private runtime directory.
3. Detect support by opening and binding the real endpoint. Operating-system name checks alone are insufficient.
4. Fall back to a server socket bound to the explicit loopback address and port zero only for documented capability/path failures.
5. Publish the selected endpoint atomically after bind and before ready state.
6. Apply connection, handshake, read, write, and idle timeouts in both transports.
7. Use the same framing, authentication, request limits, and selector/worker handoff for both transports.
8. Remove a Unix-domain socket file only while holding the matching instance lock and only after verifying it is the derived endpoint for the current instance.
9. Keep Windows named pipes and JNI/JNA transports out of scope.

## Fallback policy

Fallback is allowed when Unix-domain sockets are unavailable because of runtime or platform capability, or because no safe path can satisfy the platform length limit. Fallback is not allowed to bypass:

- an insecure state directory;
- failed ACL or mode enforcement;
- authentication initialization failure;
- an endpoint already owned by a live compatible daemon;
- a discovery record that points to a live incompatible daemon;
- state tampering that cannot be resolved safely.

The selected fallback reason is available in daemon status and debug diagnostics without exposing the state path or token unnecessarily.

## Acceptance criteria

- [x] One protocol test suite passes unchanged over Unix-domain sockets and TCP.
- [ ] Unix-domain sockets are selected on supported Windows, Linux, and macOS test hosts.
- [x] Forced unsupported/path cases select loopback TCP for only the allowlisted reasons.
- [x] No TCP listener binds beyond loopback.
- [x] Both transports enforce the same authentication and limits.
- [x] Stale socket cleanup cannot delete an arbitrary file.
- [x] Slow or malformed clients cannot hold an unbounded number of threads or buffers.
- [x] Transport-specific types remain internal to `cli-module`.

## Implementation evidence

- One transport-neutral client/server protocol suite runs over Unix-domain sockets and loopback TCP with identical authentication and frame limits.
- Windows/JDK 17 and JDK 21 plus Ubuntu 24.04/JDK 21 process tests select Unix-domain sockets when available and prove the forced, allowlisted TCP fallback; the listener is pinned to literal `127.0.0.1`. Clean Temurin 17 and 21 Linux containers independently pass both transports from the exact portable tar.
- macOS selection remains a hosted release gate, so the all-platform acceptance item and this step remain in progress.

## Stop condition

If Windows Unix-domain socket behavior is unreliable on a supported OS/JDK combination, keep the runtime capability probe and make TCP the documented result for that combination rather than adding a native pipe dependency.
