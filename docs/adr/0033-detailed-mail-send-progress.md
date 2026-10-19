# ADR 0033: Detailed connection and SMTP-stage progress

- Status: Accepted
- Decision date: 2026-10-14
- Target: 10.0.0
- Implementation: Implemented and accepted for unreleased 10.0.0
- Tracking: [#761](https://github.com/bbottema/simple-java-mail/issues/761)

## Context and decision drivers

The live progress observer can identify a slow connection acquisition or submission but cannot explain whether a server is delaying its greeting,
authentication or recipient responses. Applications need useful dashboard states without protocol debug logs, credentials or extra network work.
Providers expose different hooks, and pooled connection setup does not always belong to the email that eventually borrows that connection.

## Decision

Extend the existing MailSendProgressStage enum with greeting, capabilities, TLS, authentication, sender, recipients and DATA-permission stages.
Keep the same read interface, observation ID, bounded dispatcher and independent authoritative result. Stages describe observed work, not a required
sequence or proof of acceptance. Consumers must tolerate new enum values and coalesced updates.

An additive provider SPI observes physical setup separately from the existing submission sink. Its default is unsupported, not an error.
Setup observation is independent of abort support and upload observation. Only managed Angus paths with reliable protected hooks publish detail;
caller-owned transports remain untouched and other providers can supply any supported subset. Implicit TLS, DNS/TCP and proxy work remain coarse
where hooks do not isolate them. No reflection, debug-log scraping, extra probes or protocol implementation is introduced.

Scopes explicitly bind the initiating email's recorder to one setup or submission generation. Retiring a scope revokes its reporting authority
and releases references. Late callbacks cannot overwrite another attempt or terminal state. Cancellation retirement does not take the transport's
network monitor. The existing pool callback supplies transport and claim identity; background, reused and shared-scope setup is never replayed
as per-email work. Reporting does not change logical recovery evidence or send control.

The sink accepts only its scope's stage categories. Only orchestration owns attempt numbering and FINISHED. Optional observation registration and
cleanup failures are safely logged and ignored, without masking SMTP failures. Application callbacks remain on the existing dispatcher.
No progress registration means no added discovery, bindings or clocks.

## Alternatives considered

- A second detail field or listener: adds another dashboard concept and lifecycle without improving attribution.
- Parse debug logs or intercept private methods: fragile, risks sensitive text, and creates unsupported provider coupling.
- Replace sockets to infer DNS/TCP/TLS timing: breaks ownership and mistakes inclusive operations for precise evidence.
- Observe every pooled connection globally: falsely attributes background or historical work to the current email.
- Require detailed hooks from every provider: makes optional diagnostics a sending compatibility restriction.

## Consequences and boundaries

Applications get finer explanations where supported and honest coarse fallback elsewhere. The cost is carefully scoped observation across pooled
setup and submission, not another routing engine. There is no promise of every event or per-phase duration. DATA permission and final SMTP reply
waiting remain distinct; BDAT has no DATA-permission exchange. No content, address, credential, raw command or response text is published.
Separate upstream-hook research may improve the remaining setup visibility. PIPELINING/CHUNKING remains independent work.
[ADR 0034](0034-optional-opentelemetry-integration.md) accepts a separate OpenTelemetry integration. It must not infer
authoritative span completion or exact accounting from these coalescible stages, or enable provider detail merely to report telemetry.

## Verification obligations

Barrier-driven protocol tests must cover delayed/failing exchanges and partial/absent SPI hooks. Exercise cancellation, timeout, failover, retries,
pool replacement/reuse/background allocation and stale generations. Compare bytes, commands, reads, receipt/exception identity and disabled paths.
Verify API consumers, serialization, docs examples and the non-live reactor; observation must not alter sending behavior.

## Related decisions

- [Live progress](0032-live-mail-send-progress.md)
- [Provider boundary](0007-provider-neutral-mime-boundary.md)
- [Public API and internal construction](0031-public-api-and-internal-construction.md)
- [Provider-hook research](../research/mail-send-progress-hooks.md)
