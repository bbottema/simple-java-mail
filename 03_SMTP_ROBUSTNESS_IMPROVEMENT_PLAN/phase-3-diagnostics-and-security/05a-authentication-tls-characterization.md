# Step 5 characterization: when credentials reach the SMTP connection

- Date: 15 September 2026
- Status: Accepted baseline investigation; this checkpoint itself made no production change
- Baseline: `codex/10.0.0` at `fa0bc634`, after the accepted probe work under [#733](https://github.com/bbottema/simple-java-mail/issues/733)
- Provider tested: Angus Mail 2.0.5, with Jakarta Mail API 2.1.5 and SMTP Connection Pool 4.1.0
- Parent: [SMTP robustness improvement plan, #722](https://github.com/bbottema/simple-java-mail/issues/722)
- Follow-up: [#735](https://github.com/bbottema/simple-java-mail/issues/735) implements the accepted [construction-time guardrail](05b-mandatory-starttls-configuration-guardrail.md), without changing the opportunistic default or public API. The observations below describe the pre-change baseline, not the completed guard.

## Conclusion

There is no general "TLS failed, so try sending the password without TLS" behavior in the tested STARTTLS paths. When the server advertises STARTTLS but refuses it, presents an untrusted certificate, has a mismatched hostname, or cannot agree on a TLS protocol, the send fails before AUTH.

The permissive path is different: plain `SMTP` uses opportunistic TLS. If STARTTLS is **not advertised**, it can send a LOGIN/PLAIN password without encryption. `SMTP` is also the default when the caller supplies credentials but does not choose a strategy. Separately, extra Jakarta Mail properties can turn a mandatory STARTTLS strategy into an opportunistic one, including for OAuth2 tokens.

The maintainer has chosen to retain that opportunistic default, including when credentials are supplied, and leave mandatory TLS to the existing explicit strategies. This does not permit plaintext retries after failed TLS negotiation or validation. The remaining design question is how to handle conflicting raw properties, not whether to introduce a credential-dependent default or probe first.

## What was tested

The new [SmtpAuthenticationTlsCharacterizationTest](../../modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/internal/SmtpAuthenticationTlsCharacterizationTest.java) has 48 cases, including parameterized variations. It sends synthetic messages to scripted loopback peers using fake passwords and tokens. The peer decodes AUTH payloads to prove which credentials arrived, requires authentication to finish before accepting a message where appropriate, and checks that rejected connections close without AUTH or mail.

These tests exercise ordinary Mailer sends, not a mock probe report. Most use the actual connection pool. Additional cases exercise the non-pooling branch, asynchronous sends, a connection shared by sync/async sends, and reauthentication after a pooled connection has been closed. The only mocked module availability selects the real non-pooling send path; the socket/TLS/SMTP exchanges still run.

## Observed behavior

The raw mandatory-STARTTLS override cases below record what happened before #735. Their executable tests now expect early configuration rejection; the historical wire-level findings are retained here rather than rewritten as if the baseline already rejected them.

| Configuration and server behavior | What happens before submission |
| --- | --- |
| Default `SMTP`, no credentials, no STARTTLS | Plaintext local-relay submission works without AUTH. |
| Default `SMTP`, credentials, no STARTTLS | LOGIN or PLAIN sends the password over the unencrypted connection. Base64 is not encryption. |
| `SMTP`, STARTTLS explicitly disabled | An advertised upgrade is skipped; authentication can use plaintext. |
| `SMTP`, STARTTLS advertised but refused | The connection fails before AUTH; no plaintext continuation. |
| `SMTP` or `SMTP_TLS`, TLS handshake/trust/identity failure | The connection fails before AUTH; no plaintext continuation. |
| `SMTP_TLS`, STARTTLS missing | The connection fails before AUTH. Setting only `starttls.enable=false` does not defeat `starttls.required=true`. |
| `SMTPS` | TLS precedes the SMTP conversation. Certificate rejection prevents AUTH. Setting `mail.smtps.ssl.enable=false` alone does not disable implicit TLS. |
| `SMTP_OAUTH2`, fixed or supplied token | With its normal settings, missing/refused/untrusted TLS prevents token transmission. Successful TLS precedes XOAUTH2. |
| `SMTP_TLS` or `SMTP_OAUTH2` plus raw `mail.smtp.starttls.required=false` | With no STARTTLS advertised, the password/token reaches the plaintext connection even though `mailer.getTransportStrategy()` retains the selected strategy. |
| Configuration-source extra property with the same override | Same result as Java `withProperty(...)`; the conflict is not confined to Java callers. |

Successful results here mean final SMTP submission acceptance, not mailbox delivery.

### Certificate checks are not disabled merely by choosing SMTP

For normal SJM-owned Sessions, certificate trust uses the JVM/custom trust configuration, and server identity checking defaults to enabled, including for opportunistic SMTP. The baseline `TransportStrategy.SMTP` Javadoc incorrectly said that certificates were not validated at all. The subsequent documentation pass corrects that explanation while retaining the missing-STARTTLS warning; the connection behavior is unchanged.

The tests independently cover an empty trust store, a mismatched hostname, a TLS protocol mismatch, and an explicit `verifyingServerIdentity(false)` choice. They also cover `trustingSSLHosts("localhost")` and `trustingAllHosts(true)`: these leave the identity-checking property enabled, but deliberately relax certificate-chain trust. They must not be described as equivalent to normal trust-store validation.

Some fixture details matter when interpreting the evidence:

- STARTTLS success uses the existing test certificate as a trust anchor. Strict negative tests do not enable trust-all.
- The implicit-TLS success cases use a localhost-only trust exception, with hostname checking still enabled. Local mail shields can re-sign implicit TLS. These cases prove encryption/AUTH ordering, not acceptance by the default JVM trust store.
- Implicit-TLS rejection uses an empty custom trust store and explicitly disables socket-factory fallback. The assertion requires the trust-anchor error, not merely a generic SSL error or timeout.
- The fixture only accepts TLS 1.2; the protocol-mismatch cases explicitly offer TLS 1.3 from the client. This is a deterministic test setup, not a production TLS-version recommendation.

### Raw properties do not have uniform precedence

[MailerImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java) generates strategy properties, then copies operational extra properties into the Session, then configures trust and identity checking from the builder. As a result:

- `mail.smtp.starttls.required=false` survives and can weaken a mandatory strategy.
- Raw `mail.smtp.ssl.trust=*` is removed by the normal builder defaults.
- Raw `mail.smtp.ssl.checkserveridentity=false` is replaced by the builder's normal `true`.

The existing `Mailer.getTransportStrategy()` getter describes the selected configuration; it is not an attestation of the actual socket. No new getter or probe preflight is needed to establish that distinction.

### Token resolution and token transmission are separate events

A token supplier runs when a physical connection is opened, before TLS negotiation. That does **not** mean the token was sent. The missing-TLS tests observe one supplier call and zero AUTH commands. A reused authenticated pooled connection does not ask for another token; replacing a closed pooled connection resolves a fresh token and performs TLS before authenticating again.

The normal built-in mechanism is XOAUTH2. Angus's built-in mechanism set does not include OAUTHBEARER; naming only that mechanism fails without transmitting a token. External SASL providers were not part of this test matrix.

### Two existing Angus behaviors are relevant but distinct

1. **Configured credentials do not require authentication to occur.** If AUTH is absent from the successful EHLO response, Angus can proceed without authenticating, even with `mail.smtp.auth=true`. The scripted server then accepts the message. This happens with and without TLS. It does not disclose the credentials, but an accepted receipt must not be interpreted as proof of authentication.
2. **Failed post-TLS EHLO can leave the old AUTH list usable.** A successful post-TLS EHLO replaces the list. A failed one can leave Angus using pre-TLS mechanisms. The probe characterization already recorded the stale list; this pass additionally proves ordinary send authentication can use it. The socket is still encrypted, so this is not a plaintext downgrade. It is a separate capability-state concern: [RFC 3207 section 4.2](https://www.rfc-editor.org/rfc/rfc3207.html#section-4.2) requires pre-TLS capability knowledge to be discarded.

The source matches the observations: [Angus 2.0.5 SMTPTransport](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java) checks STARTTLS before its authentication path, conditional on its properties and the EHLO capabilities. Its [SMTP property documentation](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/package-info.java) distinguishes enabling STARTTLS from requiring it.

### Socket-factory fallback needs its own decision

An implicit-TLS connection using a custom socket factory can fail in that factory and then retry with the provider's default factory when fallback is enabled. That is not a retry in plaintext; it can nevertheless abandon custom trust/pinning/client-certificate behavior. The strict rejection tests disable that retry so they measure the intended trust failure directly.

This behavior is visible in [Angus 2.0.5 SocketFetcher](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java) and is also recorded in separate local capability-parity research. No socket-factory production setting was changed in this pass. Do not claim an unconditional custom-trust guarantee based on the STARTTLS tests.

## Ownership and scope

- SJM builds the Session configuration; Angus owns the SMTP/TLS/AUTH exchange. Pooling determines when a physical connection is opened or reused, not which TLS policy Angus enforces.
- Both execution views use that same connection behavior. A separate probe result is never consulted to authorize authentication or sending.
- A caller-owned Session without SJM's strategy marker keeps its own connection/security properties. The test confirms plaintext authentication remains possible there; normal builder security defaults are not injected into that Session.
- `CustomMailer` owns the transport. The test confirms that even selecting `SMTP_TLS` does not make SJM establish TLS on behalf of a CustomMailer.
- Results describe Angus 2.0.5, not every Jakarta Mail provider. A provider-neutral safety promise would require an enforcement contract, not just passing Angus property names to another implementation.
- SMTP TLS does not establish security for a separate proxy-authentication exchange. Existing authenticated-proxy probe tests were rerun, but this pass does not assess the security of a remote proxy hop or a production server.

The [send architecture overview](../../docs/concurrency/inside-a-mail-send.md#review-ledger) remains accurate: tests and notes add no production layer, connection owner, state machine or probe-to-send dependency.

## Maintainer decision and remaining work

On 15 September 2026, the maintainer chose usability with opportunistic TLS as the default. The earlier credential-dependent default proposal is not being taken forward:

1. Keep `SMTP` with opportunistic STARTTLS as the default, with or without username/password. Preserve port defaults and do not require a new plaintext compatibility opt-in.
2. Leave mandatory TLS to `SMTP_TLS` or `SMTPS`; retain `SMTP_OAUTH2`'s mandatory STARTTLS behavior. No new policy enum is needed.
3. Preserve fallback when STARTTLS is absent, not after a refused upgrade, failed handshake or failed certificate/identity check. Plaintext authentication remains possible under the default when STARTTLS is absent.

The proposed remaining work is to design conflict handling so raw properties cannot silently weaken a mandatory strategy, clarify caller-owned Session boundaries, and correct the stale SMTP Javadoc and property-precedence documentation. Rejection versus override is still a design choice, not an implemented behavior. Keep certificate-trust and identity-checking exceptions explicit and documented.

Treat stale post-TLS AUTH state and custom socket-factory fallback as separate hardening findings. Neither requires using the probe as a security gate.

[RFC 8314](https://www.rfc-editor.org/rfc/rfc8314.html) supports preferring protected mail submission, but does not determine this library's compatibility policy for local relays or existing applications. Retaining the default is a usability decision, not a claim that opportunistic TLS guarantees encrypted submission.

## Verification

The focused suite includes the 48 new cases, the existing 13 capability characterization cases, 52 public probe cases, 7 OAuth-provider configuration cases, and the opportunistic-TLS configuration test: **121 cases total**.

- Java 11: 121 passed; no failures or skips.
- Java 21: 121 passed; no failures or skips.
- Coding-guide audit completed for both touched test classes. `mvn license:remove -Ppublish-cli` passed; neither class has a generated license header.
- All 30 local links in the touched Markdown documents resolve, including local heading anchors. `git diff --check` passed.
- No live/public SMTP endpoints, real credentials, benchmark or SpotBugs run. No website or Journal changes.

Reproduce the focused run with the selected JDK:

```powershell
mvn.cmd -B -pl modules/simple-java-mail -am test `
  '-Dtest=SmtpAuthenticationTlsCharacterizationTest,SmtpCapabilityProbeCharacterizationTest,SmtpConnectionProbeTest,TransportStrategyOpportunisticTlsTest,OAuth2AccessTokenProviderTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-DexcludeLiveServerTests=true' `
  '-Dlicense.skip=true' '-Djacoco.skip=true' '-Dmaven.javadoc.skip=true'
```

The full non-live reactor, Spring matrix and website build are not required to characterize these test-only changes and are not claimed as verification for this pass. Run the appropriate integration verification when an actual policy/configuration change is implemented.
