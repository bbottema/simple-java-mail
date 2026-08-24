# Step 5: Define how the CLI finds and talks to its daemon

- Status: Done
- Depends on: Step 3
- Primary module: `cli-module`
- Primary areas: protocol frames, authentication, discovery record, instance lock, state permissions

## Goal

Define the internal plumbing by which a short-lived `sjm` process finds the selected local daemon, verifies its identity, sends a command, and receives the result. It must be safe over either Unix-domain sockets or loopback TCP and must not execute an application request twice merely because a client reconnects.

“Protocol” means the small local request/response message format. “Private state” means the per-user runtime files that record where the daemon is listening, which process owns it, and the random authentication secret. These are not mail configuration files and contain no Email, message body, queued mail, SMTP password, or serialized `Mailer`.

## Tests first

1. Round-trip every request and response type through an in-memory fragmented byte channel, including one-byte reads and writes.
2. Reject negative, overflowing, truncated, oversized, unknown-version, unknown-message, duplicate-field, and trailing-garbage frames before allocating attacker-controlled buffers.
3. Verify UTF-8 arguments containing spaces, newlines, Unicode, empty strings, leading `@`, and leading dashes.
4. Authenticate request headers and payloads with a fresh 256-bit daemon secret and reject altered request IDs, timestamps, payloads, and MACs.
5. Authenticate responses so a client does not render an unauthenticated exit status or failure.
6. Reject requests outside the accepted clock-skew window and replays outside the retained request-ID window.
7. Atomically replace discovery state and prove readers see either the old complete record or the new complete record, never a partial file.
8. Exercise POSIX owner/mode validation, Windows owner/ACL validation, symlink/reparse-point rejection where observable, and unsupported-permission behavior.
9. Use a path-length fixture longer than the platform Unix-domain socket limit and prove the short socket-name strategy or fallback applies.
10. Tamper with PID, start identity, socket path, port, protocol, version, instance name, and token fields and prove no unrelated process or path is touched.

## Local message format (internal protocol)

Freeze an internal length-prefixed binary protocol composed only from JDK types. Each frame includes:

- fixed magic;
- protocol major and minor version;
- message type;
- frame length;
- daemon session identifier;
- request UUID;
- request timestamp or bounded freshness value;
- bounded typed fields;
- HMAC-SHA-256 over the header and payload.

Application requests include the caller working directory and expanded argument vector. Management requests include status, stop, and readiness operations. Responses include a stable result category, CLI exit code, bounded stdout/stderr fields, daemon version, and optional structured diagnostic fields that contain no secret values.

The protocol never accepts Java serialization, Kryo payloads, arbitrary class names, reflection descriptors, or paths to objects to deserialize.

## Private discovery files (internal state)

Define one state root per operating-system user, SJM major version, and validated instance name. The state root contains only operational metadata:

- instance lock;
- discovery record;
- daemon log directory or pointer;
- optional last-start failure diagnostics that contain no secrets or message content.

The default instance and every value selected through `--daemon-instance=<name>` have separate locks, discovery data, endpoints, and logs.

The discovery record contains:

- product and protocol versions;
- daemon session identifier;
- PID and process start identity;
- transport kind;
- Unix-domain socket path or explicit loopback address and port;
- base64-encoded random authentication secret;
- ready timestamp.

Write the secret and discovery record only inside a directory proven private to the current user. POSIX state and socket permissions are owner-only. Windows ACLs grant the current SID and required system/administrator principals only; inherited broad access is removed or startup fails.

## Path safety

1. Validate instance names against a short conservative alphabet and length.
2. Derive the socket filename from a stable cryptographic hash of user, major version, and instance name.
3. Keep the socket pathname below the shortest supported platform limit; do not embed a full long home path when a short private runtime directory is available.
4. Acquire the instance lock before checking, replacing, or deleting an old socket.
5. Delete only the exact socket path derived by the current process under the validated runtime directory.
6. Never follow a discovery-file path to delete a file.

## Limits to freeze

Record conservative defaults and hard maxima for:

- frame size;
- argument count and bytes per argument;
- working-directory length;
- stdout/stderr bytes retained in a terminal response;
- concurrent client connections;
- authentication failures per interval;
- request freshness and recent-ID retention.

These are internal operational limits, but exceeding one must return a stable result category where a valid authenticated response remains possible.

## Acceptance criteria

- [x] The complete byte-level protocol and version-negotiation behavior are documented in tests.
- [x] Every allocation based on input is length-checked first.
- [x] Requests and responses are authenticated without transmitting the secret as a loggable argument.
- [x] Replaying one request ID cannot execute the application command twice.
- [x] Discovery writes are atomic and private.
- [x] Runtime state contains only daemon-discovery/lifecycle metadata, never mail content or Mailer configuration.
- [x] State, socket, symlink, ACL, and path-length attacks have named tests.
- [x] No serialization library or new runtime dependency is needed.
- [x] The protocol has no remote-listening mode.

## Completion evidence

- Protocol tests cover the fixed binary envelope, HMAC authentication, exact version checks, freshness, replay behavior, malformed/trailing input, and all documented size limits.
- State-store tests cover atomic discovery, owner-only permissions/ACL handling, invalid UTF-8, symlinks, overlong socket paths, tampering, PID/start identity, and stale cleanup.
- Discovery contains only lifecycle/endpoint metadata and a 256-bit authentication secret; configuration and mail content remain request-local and in memory.

## Stop condition

If private ownership or permissions cannot be established for the selected state directory, fail daemon startup. Do not weaken authentication or silently place the token in a broadly readable temporary directory.
