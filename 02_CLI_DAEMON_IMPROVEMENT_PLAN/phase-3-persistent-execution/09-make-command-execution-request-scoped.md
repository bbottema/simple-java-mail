# Step 9: Make parsing, files, and output request-scoped

- Status: Done
- Depends on: Steps 3, 4, and 8
- Primary module: `cli-module`
- Primary files: `CliSupport.java`, `CliCommandLineConsumer.java`, `CliCommandLineConsumerResultHandler.java`, CLI value interpreters, command-tree producer

## Goal

Extract one command executor that can run safely either in the current process or inside a concurrent daemon without shared parser state, global working-directory changes, or global output redirection.

## Tests first

1. Execute send, connect, and validate through a synthetic request context and compare parsed options, exit codes, stdout, stderr, and failures with the Step 1 one-shot baseline.
2. Run two requests concurrently with different working directories and same relative filenames. Assert each reads only its own attachment, body, certificate, EML/MSG, and argument files.
3. Cover paths with spaces, Unicode, `..`, symlinks where supported, missing files, directories where files are required, and files replaced between expansion and use.
4. Cover Picocli argument-file quoting, escaping, comments, nested argument files, recursion, and size/depth limits.
5. Run two parse operations concurrently with conflicting options and prove no Picocli `CommandLine`, parse result, converter, usage buffer, or received-option collection is shared mutably.
6. Capture stdout and stderr per request without replacing `System.out`, `System.err`, or the global logging configuration.
7. Disconnect a client while command output is being produced and prove the daemon retains a bounded terminal result without blocking on the dead client.
8. Run the same executor through one-shot and daemon adapters and compare observable results.
9. Assert management and thin-client parsing still avoid the full generated command tree.
10. Fuzz argument counts, lengths, quoting, invalid UTF-8 at the protocol boundary, and options that resemble daemon bootstrap options after the subcommand.

## Implementation

1. Introduce an internal immutable `CliRequestContext` containing request ID, caller working directory, argument vector, output sinks/buffers, execution route, and cancellation/shutdown observation.
2. Expand `@argument` files in the client against the caller working directory before the request is authenticated and sent. Preserve a bounded original/expanded diagnostic without secret values.
3. Make all file-based value interpreters resolve through the request context. Do not use daemon startup CWD and never assign `System.setProperty("user.dir", ...)`.
4. Create a fresh mutable Picocli command tree or parse session per request from immutable generated descriptors. If full tree creation is expensive, cache only immutable descriptors and verified reflection handles.
5. Separate parsing from execution. A parsed command becomes an immutable internal command/specification before a Mailer or Email is built.
6. Replace direct console logging/output in command execution with request-owned output channels. Interactive one-shot mode adapts these channels to the real console.
7. Preserve the current synchronous wait behavior and existing result categories. Mailer ownership changes in Steps 10 and 11, not here.
8. Bound output retained for a disconnected client and return an explicit truncation marker/category when the public limit is reached.

## File semantics

- Relative paths mean relative to the invoking client process.
- The daemon reads the canonicalized/resolved path under the same OS identity in the per-user mode.
- The initial protocol does not upload file bytes.
- A daemon can read only paths granted to its operating-system identity; packaging and supervisor documentation must not promise otherwise.
- A time-of-check/time-of-use file replacement remains ordinary filesystem behavior and is not hidden by caching input content.

## Acceptance criteria

- [x] One command executor serves both one-shot and daemon adapters.
- [x] Two concurrent request working directories cannot cross-resolve files.
- [x] `user.dir`, `System.out`, `System.err`, and global Picocli parse state are not mutated.
- [x] Argument-file behavior matches the caller's current one-shot semantics within documented limits.
- [x] Per-request output and errors match Step 1.
- [x] Full command parsing remains absent from management/thin-client paths.
- [x] Malformed or oversized arguments fail before builder invocation.
- [x] No Mailer reuse is introduced until Step 10 defines safe identity.

## Completion evidence

- `CliSupport` and `CliExecutionEnvironment` provide one executor for one-shot and daemon adapters with a fresh Picocli tree and request-owned context/output per invocation.
- Tests prove concurrent working directories remain isolated and production code does not mutate `user.dir`, `System.out`, or `System.err`.
- Client-side argument-file expansion preserves caller-relative paths and quoting while enforcing nesting, UTF-8, count, per-argument, aggregate, and output limits before builder invocation.

## Stop condition

If Picocli cannot parse concurrent requests without shared mutable command state, serialize parsing behind a narrow lock or rebuild a command tree per request. Do not serialize SMTP execution unnecessarily and do not reuse a mutated parser.
