# Step 10: Build the SMTP conformance and fault-injection suite

- Status: Proposed
- Depends on: Starts from the existing fault-boundary tests and expands alongside Steps 1 through 9
- Proposed child issue: `Publish reproducible SMTP conformance and fault-injection evidence`
- Proposed classification: `maintenance`; add no functionality label unless this step later exposes a user-facing test kit
- Release sensitivity: Evidence can grow incrementally, but no broad robustness claim should depend on unpublished or non-reproducible results
- Primary areas: test infrastructure, CI, scripted SMTP peers, containerized MTAs, benchmark tooling, release evidence

## Goal

Turn the current scripted SMTP fault-boundary test into a reproducible suite that proves protocol outcomes, cleanup, content integrity, and operational limits against both pathological peers and real SMTP implementations.

The suite is not a replacement for focused unit tests. It is the integration evidence that the public contracts still hold when sockets split, replies are malformed, servers disagree, and timing creates races.

## Test layers

### 1. Deterministic scripted peer

Extend `SmtpSubmissionFaultBoundaryTest` or extract a reusable test peer that can pause, reply, reject, reset, time out, or close at every phase:

- greeting and EHLO;
- STARTTLS negotiation and post-TLS EHLO;
- AUTH challenge and result;
- MAIL FROM;
- each RCPT TO in a mixed-recipient transaction;
- DATA invitation;
- message content and final terminator;
- final acceptance response;
- RSET, QUIT, and pooled connection reuse.

Support multiline responses, malformed primary and enhanced codes, delayed bytes, short reads/writes, unexpected commands, and configurable recipient response order.

### 2. Real SMTP implementations

Provide reproducible container or local profiles for representative versions of:

- Postfix;
- Exim;
- OpenSMTPD where the CI platform can run it reliably;
- a deliberately minimal or test-focused SMTP peer used by existing project tests.

Record exact image/version digests and configuration. Do not require Docker for the ordinary unit-test lane; run the real-MTA matrix in a named integration profile or CI job.

### 3. Content-integrity interoperability

Verify composed and exact messages using independent tools where practical:

- DKIM verification after SMTP transparency handling;
- S/MIME signing/encryption verification;
- OpenPGP/MIME verification;
- exact EML byte identity before SMTP framing;
- SMTPUTF8, 8BITMIME, DSN, REQUIRETLS, and SIZE wire behavior as their steps land.

### 4. Operational stress and performance

Measure rather than merely assert:

- queue and pool saturation;
- cancellation latency at each protocol phase;
- pool fairness and lease cleanup;
- connection reuse after success and disposal after corruption;
- large-message peak memory and transfer progress;
- throughput and tail latency with pooling across low- and high-latency simulated links.

PIPELINING or CHUNKING comparisons remain excluded while #699 is parked.

## Harness requirements

1. Every scenario has a fixed upper timeout and guaranteed socket/process/container cleanup.
2. Scenario names describe the protocol boundary and expected observable outcome.
3. Randomized or fuzz cases print a reproducible seed on failure.
4. Test logs redact credentials and message content unless a fixture contains only declared non-secret test data.
5. Results record the SJM commit, modules, Angus version, JDK, crypto provider, operating system, and server version/configuration.
6. Raw machine-readable results and a short human summary use stable schemas suitable for release comparison.
7. The suite can be run by an independent developer from checked-in instructions.
8. A failure in optional external infrastructure is reported separately from a protocol assertion failure.

## Tests to retain from the current seed

- disconnect before message submission remains known rejected;
- reset or EOF around final DATA acceptance remains unknown;
- explicit final rejection remains rejected;
- final 250 remains accepted even if the connection closes afterward;
- mixed recipient outcomes preserve only attempt-local facts;
- a corrupted pooled transport is invalidated before the next attempt.

Each preceding plan step adds its own scenarios to this shared suite rather than building a private one-off server.

## Evidence and release workflow

- Add a documented command for the deterministic suite and each external profile.
- Publish raw results and the rendered summary for release candidates selected for an assurance claim.
- Attach hashes or provenance sufficient to connect the result to the tested commit and dependency set.
- Keep pass/fail criteria versioned in the repository so a later release cannot silently weaken an older comparison.
- Describe exactly which modules, JDK, Angus, crypto provider, SMTP servers, and operating configurations were tested.

This step does not by itself create a certification or guarantee. It supplies evidence for narrowly scoped public claims.

## Acceptance criteria

- [ ] Every SMTP phase can be delayed, failed, rejected, or disconnected deterministically.
- [ ] Mixed-recipient and final-commit boundaries have stable expected outcomes.
- [ ] Pool and executor cleanup is proven after every failure category.
- [ ] At least two materially different real SMTP implementations pass the applicable matrix.
- [ ] Exact and protected content passes independent verification.
- [ ] Stress runs report queue bounds, memory, throughput, and tail latency reproducibly.
- [ ] Results identify the complete tested runtime and contain no secrets.
- [ ] An independent developer can run the documented suite and reproduce the published result format.

## Stop condition

If a scenario depends on unstable public container tags, live third-party accounts, or nondeterministic internet services, move it out of the release gate or pin a controlled replacement. Do not base a robustness claim on an environment that another developer cannot reproduce.
