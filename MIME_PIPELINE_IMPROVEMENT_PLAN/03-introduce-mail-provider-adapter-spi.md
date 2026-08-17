# Step 3 — Introduce the mail-provider adapter SPI

- Status: Done
- Depends on: Step 2
- Primary areas: provider SPI, adapter discovery, `TransportRunner`, JPMS service declarations

## Goal

Give the selected Jakarta Mail provider ownership of the final send operation without letting provider types enter the MIME pipeline. The SPI must be narrow enough to support third-party adapters and stable enough to be a supported 10.0.0 extension point.

## Adapter responsibilities

A `MailTransportAdapter` implementation must be able to:

- declare whether it supports the actual `Session`/`Transport` pair;
- send a `PreparedMail` without changing protected content bytes;
- apply supported envelope-sender and DSN options;
- return provider-specific submission details when available; and
- report an unsupported requested capability before submission rather than ignoring it.

## Work

1. Place the SPI and provider-neutral argument/result types in a small exported package containing no implementation classes.
2. Discover adapters with Java 8-compatible `ServiceLoader`; add corresponding JPMS `uses`/`provides` declarations.
3. Define deterministic selection, including zero-match, one-match, and ambiguous-match behavior.
4. Supply a generic fallback that can call `Transport.sendMessage` only when no provider-specific delivery capability was requested.
5. Move `TransportRunner` to the SPI without changing batching or connection ownership.
6. Test the SPI with a minimal fake Jakarta Mail provider/transport that has no Angus classes on its classpath.

## Acceptance criteria

- [x] SPI signatures expose only JDK, Jakarta Mail, and provider-neutral Simple Java Mail types.
- [x] Adapter lookup behaves identically on the classpath and module path.
- [x] An unknown provider can send an ordinary message through the generic fallback.
- [x] An unknown provider requesting bounce or DSN fails with an actionable capability error before sending.
- [x] Multiple matching adapters fail deterministically and name the conflicting implementations.
- [x] Batch-module transports use the adapter selected for the actual underlying transport.
- [x] No provider adapter can signal successful submission without the normal Mailer receipt path completing.

## API review gate

Freeze package and artifact names only after compiling one external sample adapter against the proposed SPI. The SPI should not expose mutable pipeline internals merely to make the built-in Angus adapter easier to write.

## Completion evidence

- The exported `org.simplejavamail.api.mailer.spi` package contains `MailTransportAdapter`, `PreparedMail`, and `DeliveryEnvelope` and exposes no provider implementation type.
- `MailTransportAdapterResolverTest` covers one match, generic fallback, unsupported capabilities, and deterministic ambiguity errors; `TransportRunnerTest` covers adapter delegation and receipt completion.
- `META-INF/services` and JPMS `uses`/`provides` declarations exercise the same `ServiceLoader` contract.
- The classpath and JPMS provider-neutral consumers register a fake Jakarta `Transport`, assert that Angus cannot be loaded, and send an ordinary message through the generic fallback.
- `MailerTest` exercises direct, open-connection, asynchronous, and batch receipt paths on the same adapter boundary.
