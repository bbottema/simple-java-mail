package org.simplejavamail.api.mailer.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.internal.util.DurationMillis;
import java.time.Duration;
import static java.util.Objects.requireNonNull;

/**
 * Immutable supplied ConnectionConfig choices. Unset fields inherit the owning Mailer factory snapshot.
 * Construction and inspection never start workers, connect, resolve configuration sources or own borrowed resources.
 * Replacing this group on a Mailer discards its previous supplied choices.
 */
@Value
public class ConnectionConfig {
    @Nullable Duration sessionTimeout;
    @Nullable String localBindAddress;
    boolean localBindAddressCleared;
    @Nullable Integer localBindPort;
    boolean localBindPortCleared;
    @Nullable String smtpClientHostname;
    boolean smtpClientHostnameCleared;
    @Nullable Boolean legacySmtpContentSupport;

    private ConnectionConfig(final Builder builder) {
        sessionTimeout = builder.sessionTimeout;
        DurationMillis.toNullableInt(sessionTimeout, "sessionTimeout");
        localBindAddress = builder.localBindAddress;
        localBindAddressCleared = builder.localBindAddressCleared;
        localBindPort = builder.localBindPort;
        localBindPortCleared = builder.localBindPortCleared;
        smtpClientHostname = builder.smtpClientHostname;
        smtpClientHostnameCleared = builder.smtpClientHostnameCleared;
        legacySmtpContentSupport = builder.legacySmtpContentSupport;
    }

    /** @return A builder containing no supplied choices. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining supplied choices and borrowed-resource identities. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /** @see Builder#withSessionTimeout(Duration) */
    @Nullable public Duration getSessionTimeout() { return sessionTimeout; }
    /** @see Builder#withLocalBindAddress(String) */
    @Nullable public String getLocalBindAddress() { return localBindAddress; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearLocalBindAddress()
     */
    public boolean isLocalBindAddressCleared() { return localBindAddressCleared; }
    /** @see Builder#withLocalBindPort(Integer) */
    @Nullable public Integer getLocalBindPort() { return localBindPort; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearLocalBindPort()
     */
    public boolean isLocalBindPortCleared() { return localBindPortCleared; }
    /** @see Builder#withSmtpClientHostname(String) */
    @Nullable public String getSmtpClientHostname() { return smtpClientHostname; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearSmtpClientHostname()
     */
    public boolean isSmtpClientHostnameCleared() { return smtpClientHostnameCleared; }
    /** @see Builder#withLegacySmtpContentSupport(boolean) */
    @Nullable public Boolean getLegacySmtpContentSupport() { return legacySmtpContentSupport; }

    /** Explicit choices for this group; inspection does not build or validate an incomplete configuration. */
    public static final class Builder {
        private Duration sessionTimeout;
        private String localBindAddress;
        private boolean localBindAddressCleared;
        private Integer localBindPort;
        private boolean localBindPortCleared;
        private String smtpClientHostname;
        private boolean smtpClientHostnameCleared;
        private Boolean legacySmtpContentSupport;
        private Builder() { }
        private Builder(final ConnectionConfig config) {
            sessionTimeout = config.sessionTimeout;
            localBindAddress = config.localBindAddress;
            localBindAddressCleared = config.localBindAddressCleared;
            localBindPort = config.localBindPort;
            localBindPortCleared = config.localBindPortCleared;
            smtpClientHostname = config.smtpClientHostname;
            smtpClientHostnameCleared = config.smtpClientHostnameCleared;
            legacySmtpContentSupport = config.legacySmtpContentSupport;
        }
        /**
         * Sets the same socket timeout for connecting, reading and writing. This is not a deadline for the whole send.
         * <p>
         * When you supply your own Session, its existing timeouts remain unchanged until you call this method or a factory lock requires a value.
         * For example, a Session with a five-second read timeout keeps that value; calling {@code withSessionTimeout(Duration.ofSeconds(1))} deliberately replaces
         * all three socket timeouts with one second. Ordinary SJM connection defaults do not replace caller-supplied settings, even when the Session
         * previously belonged to another Mailer. Additional Jakarta Mail properties retain their configured overlay behavior.
         * <p>
         * The property names follow the selected {@code smtp} or {@code smtps} protocol:
         * <ul>
         *     <li>{@link TransportStrategy#propertyNameConnectionTimeout()}</li>
         *     <li>{@link TransportStrategy#propertyNameTimeout()}</li>
         *     <li>{@link TransportStrategy#propertyNameWriteTimeout()}</li>
         * </ul>
         * On owned managed Angus Sessions, enabled write timeouts share one lazy daemon scheduler per Session/protocol. Its worker stops after the
         * last physical connection is disposed; returning a pooled lease or aborting a raw socket is not disposal. A supplied
         * {@code mail.smtp.executor.writetimeout} (or {@code mail.smtps} equivalent) remains application-owned and is not shut down by the Mailer.
         * Caller-owned Sessions and other providers keep their existing timeout-resource ownership. This is not a total-send deadline.
         * <p>
         * The builder getter reports its configured choice. After construction, {@link OperationalConfig#getSessionTimeout()} reports the common
         * configured timeout, or {@code null} if a caller-supplied Session or locked raw properties leave different, missing or unusable values.
         * A custom protocol cannot use these SMTP convenience mappings; configure its provider-specific settings through {@link org.simplejavamail.api.mailer.MailerGenericBuilder#withProperty(String, Object)}.
         *
         * @param sessionTimeout Socket timeout, exactly representable as an integer number of milliseconds.
         * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
         */
        @NotNull public Builder withSessionTimeout(final Duration sessionTimeout) { DurationMillis.toNullableInt(sessionTimeout, "sessionTimeout"); this.sessionTimeout = requireNonNull(sessionTimeout, "sessionTimeout"); return this; }
        /** @see #withSessionTimeout(Duration) */
        @Nullable public Duration getSessionTimeout() { return sessionTimeout; }
        /**
         * Selects the published library fallback.
         * @see #withSessionTimeout(Duration)
         */
        @NotNull public Builder resetSessionTimeout() { return withSessionTimeout(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT); }
        /**
         * Restores captured configuration for this member.
         * @see #withSessionTimeout(Duration)
         */
        @NotNull public Builder useConfiguredSessionTimeout() { sessionTimeout = null; return this; }
        /**
         * Sets the local/source address without changing the already configured local/source port.
         *
         * @param localBindAddress Local/source address or host name to bind the SMTP socket to, or {@code null} to remove that direct setting.
         *
         * @see #withLocalBindAddress(String, Integer)
         * @see #clearLocalBindAddress()
         * @see TransportStrategy#propertyNameLocalAddress()
         */
        @NotNull public Builder withLocalBindAddress(final String localBindAddress) { this.localBindAddress = localBindAddress; localBindAddressCleared = localBindAddress == null; return this; }
        /** @see #withLocalBindAddress(String) */
        @Nullable public String getLocalBindAddress() { return localBindAddress; }
        /**
         * Removes both the local/source address and port, including captured choices, rather than restoring captured configuration.
         * A caller-supplied Session's inherited Properties defaults cannot be removed this way; see
         * {@link MailerFromSessionBuilder#usingSession(jakarta.mail.Session)}.
         * @see #withLocalBindAddress(String)
         */
        @NotNull public Builder clearLocalBindAddress() { localBindAddress = null; localBindAddressCleared = true; return clearLocalBindPort(); }
        /**
         * Restores captured configuration for the address only; the current local/source port choice remains unchanged.
         * @see #withLocalBindAddress(String)
         */
        @NotNull public Builder useConfiguredLocalBindAddress() { localBindAddress = null; localBindAddressCleared = false; return this; }
        /**
         * Replaces only the local socket port. Null explicitly removes a captured port.
         * @param localBindPort Port to bind, or null.
         */
        @NotNull public Builder withLocalBindPort(final Integer localBindPort) { this.localBindPort = localBindPort; localBindPortCleared = localBindPort == null; return this; }
        /** @see #withLocalBindPort(Integer) */
        @Nullable public Integer getLocalBindPort() { return localBindPort; }
        /**
         * Removes this choice rather than restoring captured configuration.
         * @see #withLocalBindPort(Integer)
         */
        @NotNull public Builder clearLocalBindPort() { localBindPort = null; localBindPortCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withLocalBindPort(Integer)
         */
        @NotNull public Builder useConfiguredLocalBindPort() { localBindPort = null; localBindPortCleared = false; return this; }
        /**
         * Configures the hostname that Jakarta Mail should send as the SMTP client identity in the {@code EHLO} or
         * {@code HELO} command.
         * <p>
         * This is useful when corporate SMTP relays expect a stable recognizable client hostname, or when mail-server logs,
         * tracing, audit trails and SMTP diagnostics should identify application traffic consistently. Simple Java Mail applies
         * this to the correct Jakarta Mail Session property for the configured {@link TransportStrategy}.
         * <p>
         * This setting does not bind the outgoing socket to a local IP address; use {@link #withLocalBindAddress(String, Integer)}
         * for that. It also does not change the SMTP envelope sender address.
         * <p>
         * Provide the hostname shape expected by your SMTP relay, typically a DNS host name or FQDN. Simple Java Mail deliberately
         * does not over-validate this value because corporate relay identifiers can be environment-specific.
         *
         * @param smtpClientHostname Hostname to send in the SMTP {@code EHLO} or {@code HELO} command, or {@code null} to remove this direct setting.
         *
         * @see #clearSmtpClientHostname()
         * @see TransportStrategy#propertyNameLocalHost()
         * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.localhost">mail.smtp.localhost</a>
         */
        @NotNull public Builder withSmtpClientHostname(final String smtpClientHostname) { this.smtpClientHostname = smtpClientHostname; smtpClientHostnameCleared = smtpClientHostname == null; return this; }
        /** @see #withSmtpClientHostname(String) */
        @Nullable public String getSmtpClientHostname() { return smtpClientHostname; }
        /**
         * Removes this choice rather than restoring captured configuration.
         * @see #withSmtpClientHostname(String)
         */
        @NotNull public Builder clearSmtpClientHostname() { smtpClientHostname = null; smtpClientHostnameCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withSmtpClientHostname(String)
         */
        @NotNull public Builder useConfiguredSmtpClientHostname() { smtpClientHostname = null; smtpClientHostnameCleared = false; return this; }
        /**
         * Allows an unchanged send attempt through a known legacy SMTP server that does not announce support for the email's addresses or content.
         * <p>
         * For example, an accented display name is different from an accented mailbox address:
         * <ul>
         * <li>{@code José <jose@example.org>}: only the display name contains an accent. Simple Java Mail MIME-encodes the name, so it does not require
         * special support for internationalized addresses. Ordinary Unicode subjects and message text also use MIME encoding and do not need this setting.</li>
         * <li>{@code josé@example.org}: the accent is part of the actual mailbox address. MIME encoding cannot make that address ASCII; sending it normally
         * requires the server to announce {@code SMTPUTF8} support.</li>
         * </ul>
         * Simple Java Mail detects and uses advertised SMTPUTF8 support automatically; leave this setting disabled for those servers. If the server does
         * not announce that support, Simple Java Mail rejects a send requiring it before submitting the email. It does not assume the server can handle it.
         * <p>
         * Enable this only when you have verified that your server and its onward delivery route can handle the original address and content despite
         * not announcing support. For example, an older relay you control might correctly accept {@code josé@example.org} without announcing SMTPUTF8.
         * This option lets Simple Java Mail try that address unchanged; it does not turn {@code josé@example.org} into {@code jose@example.org}, or make
         * an incompatible server support internationalized addresses. Server acceptance still does not prove onward preservation or final delivery.
         * <p>
         * The same permission covers raw UTF-8 headers and raw 8-bit bodies, such as those already present in an exact EML file. Those are different from
         * the normally MIME-encoded subjects and bodies described above. Defaults to {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT}. The bundled Angus adapter
         * still uses advertised SMTPUTF8/8BITMIME extensions when available; otherwise it attempts the unchanged submission without automatically adding
         * unadvertised extension parameters. This operates outside negotiated SMTPUTF8/8BITMIME guarantees. Rejection is reported normally, without a retry
         * that changes the address or content.
         * <p>
         * This does not rewrite addresses or exact/signed content, bypass malformed/binary-content checks, weaken TLS or REQUIRETLS, or relax explicit DSN
         * requirements. It does not override explicitly disabled UTF-8 encoding. Repeated calls replace the choice; {@code false} disables the opt-in.
         * {@link #resetLegacySmtpContentSupport()} restores the built-in default, not a captured property value. Configuration locks still apply.
         * In a shared connection pool, the selected server's Mailer configuration applies. CustomMailer and third-party providers retain their own behavior.
         * Caller-owned ordinary Angus Sessions retain their Session-wide SMTPUTF8 declaration behavior. Library-owned stock Angus Sessions negotiate
         * per message, also with custom socket factories; the factories and their settings are preserved without adding physical-abort support.
         *
         * @param legacySmtpContentSupport Whether to try the original address and content even when this verified legacy server does not announce the required support.
         * @see OperationalConfig#isLegacySmtpContentSupportEnabled()
         * @see <a href="https://www.rfc-editor.org/rfc/rfc6531.html#section-3.2">SMTPUTF8 negotiation</a>
         * @see <a href="https://www.rfc-editor.org/rfc/rfc6152.html#section-3">8BITMIME negotiation</a>
         */
        @NotNull public Builder withLegacySmtpContentSupport(final boolean legacySmtpContentSupport) { this.legacySmtpContentSupport = legacySmtpContentSupport; return this; }
        /** @see #withLegacySmtpContentSupport(boolean) */
        @Nullable public Boolean getLegacySmtpContentSupport() { return legacySmtpContentSupport; }
        /**
         * Selects the published library fallback.
         * @see #withLegacySmtpContentSupport(boolean)
         */
        @NotNull public Builder resetLegacySmtpContentSupport() { return withLegacySmtpContentSupport(MailerGenericBuilder.DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT); }
        /**
         * Restores captured configuration for this member.
         * @see #withLegacySmtpContentSupport(boolean)
         */
        @NotNull public Builder useConfiguredLegacySmtpContentSupport() { legacySmtpContentSupport = null; return this; }
        /**
         * Configures the local/source address and optional local/source port that Jakarta Mail should bind the outgoing
         * SMTP socket to.
         * <p>
         * This is useful on machines with multiple local IP addresses when the remote SMTP server expects the connection
         * to originate from a specific address. Simple Java Mail applies this to the correct Jakarta Mail Session property
         * for the configured {@link TransportStrategy}.
         * <p>
         * Leave {@code localBindPort} empty unless you specifically need to bind a fixed local port. Binding the local port is
         * an advanced setting and can cause conflicts if another connection already uses that port.
         * <p>
         * If a remote SOCKS proxy is used, the SMTP server usually sees the proxy's outgoing IP address. With Simple Java
         * Mail's authenticated SOCKS bridge, this setting binds Jakarta Mail's socket to the local bridge/proxy path, not
         * the proxy server's own outbound socket.
         * <p>
         * A caller-supplied Session keeps its local binding unless you use this setter. A null argument removes that direct Session property;
         * an inherited Properties default cannot be removed this way. See {@link MailerFromSessionBuilder#usingSession(jakarta.mail.Session)}.
         *
         * @param localBindAddress Local/source address or host name to bind the SMTP socket to, or {@code null} to remove this direct setting.
         * @param localBindPort Optional local/source port to bind the SMTP socket to, or {@code null} to remove this direct setting.
         *
         * @see #clearLocalBindAddress()
         * @see TransportStrategy#propertyNameLocalAddress()
         * @see TransportStrategy#propertyNameLocalPort()
         * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.localaddress">mail.smtp.localaddress</a>
         */
        @NotNull public Builder withLocalBindAddress(final String localBindAddress, final Integer localBindPort) {
            return withLocalBindAddress(localBindAddress).withLocalBindPort(localBindPort);
        }
        /** @return An immutable snapshot; feature validation remains with its owning runtime. */
        @NotNull public ConnectionConfig build() { return new ConnectionConfig(this); }
    }
}
