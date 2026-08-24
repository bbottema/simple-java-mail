# Step 13: Harden secrets, logging, replacement, and shutdown

- Status: Done
- Depends on: Steps 10 through 12
- Primary files: CLI `log4j2.xml`, command consumer/handler logging, daemon lifecycle and status components
- Primary areas: redaction, rolling logs, configuration replacement, OAuth2 behavior, graceful drain

## Goal

Make the long-lived process safe to operate: no persistent secret/body leakage, no misleading hot reload, and one bounded shutdown sequence that retires every accepted request and Mailer correctly.

## Tests first

1. Run commands containing synthetic SMTP/proxy passwords, OAuth tokens, message bodies, private-key text, certificate paths, recipient addresses, and daemon tokens. Scan stdout, stderr, daemon logs, discovery state, exceptions, status, test reports, and heap-diagnostic labels where available.
2. Trigger parser, builder, authentication, SMTP, pool, eviction, close, and startup failures and repeat the scan.
3. Rotate logs under sustained requests and prove count/size retention is bounded and active writes continue.
4. Make log files unreadable, unwritable, full, renamed, and externally rotated. Freeze fail/start behavior without falling back to trace console output.
5. Change configuration files, environment, system properties, certificates, and OAuth token values after startup. Prove no live Mailer changes and status reports the captured generation.
6. Restart with changed configuration and prove new requests use only the replacement generation.
7. Initiate stop with idle Mailers, queued requests, active sends, completed-but-unread results, and close failures.
8. Verify shutdown order and timeout diagnostics on Windows process termination, Unix termination signal, authenticated stop, and JVM shutdown hook.
9. Assert current debug logging of converted option values and trace-level MIME/message output is absent from daemon defaults.
10. Scan source for raw request vectors, secrets, token-bearing objects, and Email/MimeMessage `toString()` values passed to logging APIs.

## Logging implementation

1. Give daemon mode a dedicated configuration with bounded rolling files and a concise startup failure path.
2. Keep interactive one-shot output separate from operational daemon logs.
3. Log request IDs, result categories, durations, queue state, and anonymous counts. Do not log raw arguments or full profile fingerprints by default.
4. Centralize secret classification/redaction and make sensitive wrapper types redact in `toString()`.
5. Remove or guard current option-value debug statements and the trace-heavy `MailerImpl` default for daemon operation.
6. Never log Email, MimeMessage, DataSource content, authentication MACs, or discovery secrets.

## Replacement contract

- Configuration is captured once during daemon startup.
- `daemon restart` is the initial reload mechanism.
- Existing Mailers are never mutated in place.
- A replacement daemon does not publish ready until its configuration, endpoint, executor, and registry are initialized.
- The old daemon stops intake, drains accepted work, closes Mailers, removes state, and terminates before the replacement claims the same instance.
- A fixed OAuth2 token changes only through a new request profile or daemon restart. No external command is executed to retrieve tokens.

## Shutdown order

1. Transition to `QUIESCING` and reject new application requests.
2. Close the listening endpoint while keeping already accepted authenticated connections alive as required.
3. Reject queued work that has not crossed the frozen acceptance boundary.
4. Wait for accepted command futures and record their terminal results within the configured grace period.
5. Stop idle eviction and remove registry entries from acquisition.
6. Close every Mailer after its active leases/futures finish.
7. Shut down daemon executors and logging cleanly.
8. Remove discovery/socket state while holding the instance lock, then release the lock.

If the grace period expires, report which non-secret category remains and terminate according to the documented service-manager policy. Never report a clean send result that was not observed.

## Acceptance criteria

- [x] Synthetic secrets and message bodies appear nowhere in operational artifacts.
- [x] Daemon logs rotate and remain bounded.
- [x] Raw CLI options and Mailer trace/MIME output are absent by default.
- [x] Configuration replacement occurs only through new objects and restart.
- [x] Shutdown ordering is one idempotent implementation shared by all stop sources.
- [x] Accepted work is drained or reported ambiguous according to Step 12.
- [x] Every Mailer, batch registration, executor, channel, and lock is retired.
- [x] OAuth2 limitations are explicit and no token-provider command exists.
- [x] Phase 3 end-to-end tests prove repeated Mailer and SMTP-pool reuse.

## Completion evidence

- Daemon logging is isolated from application/Mailer trace logging and rolls at 10 MiB with five retained files.
- Process tests inject unique synthetic credentials/tokens and scan state and log artifacts; raw arguments, MIME bodies, profile material, and secrets are absent.
- The shared quiesce path closes intake, drains accepted work within bounds, records ambiguity where required, retires Mailers and executors, and releases endpoint/state ownership idempotently.

## Stop condition

If any supported failure path writes a credential, token, private key, message body, or daemon secret to persistent logs, block platform packaging until the exposure is removed and covered by a regression test.
