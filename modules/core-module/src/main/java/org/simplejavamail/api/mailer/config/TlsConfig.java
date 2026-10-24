package org.simplejavamail.api.mailer.config;

import lombok.Value;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.Mailer;
import javax.net.ssl.SSLSocketFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static java.util.Objects.requireNonNull;

/**
 * Immutable supplied TLS choices. Unset fields use the applicable connection baseline and factory configuration when the Mailer is built.
 * Construction and inspection never start workers, connect, resolve configuration sources or own borrowed resources.
 * Replacing this group on a Mailer discards its previous supplied choices.
 * Custom SSL factories and opportunistic-upgrade choices apply only when Simple Java Mail constructs the Session.
 * Configure those choices on an application-supplied Session itself; supplying them through this group on that builder is rejected.
 * Replacing or removing imported TLS settings follows {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)} when the Mailer is built.
 * The default rejects conflicting explicit settings; configuration locks and mandatory transport requirements remain binding in both modes.
 */
@Value
public class TlsConfig {
    @Nullable List<String> sslHostsToTrust;
    @Nullable Boolean trustAllHosts;
    @Nullable Boolean verifyingServerIdentity;
    @Nullable Boolean opportunisticTLS;
    @Nullable String customSSLFactoryClass;
    boolean customSSLFactoryClassCleared;
    @Nullable @ToString.Exclude SSLSocketFactory customSSLFactoryInstance;
    boolean customSSLFactoryInstanceCleared;

    private TlsConfig(final Builder builder) {
        sslHostsToTrust = builder.sslHostsToTrust == null ? null : Collections.unmodifiableList(new ArrayList<>(builder.sslHostsToTrust));
        trustAllHosts = builder.trustAllHosts;
        verifyingServerIdentity = builder.verifyingServerIdentity;
        opportunisticTLS = builder.opportunisticTLS;
        customSSLFactoryClass = builder.customSSLFactoryClass;
        customSSLFactoryClassCleared = builder.customSSLFactoryClassCleared;
        customSSLFactoryInstance = builder.customSSLFactoryInstance;
        customSSLFactoryInstanceCleared = builder.customSSLFactoryInstanceCleared;
    }

    /** @return A builder containing no supplied choices. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining supplied choices and borrowed-resource identities. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /**
     * @return An unmodifiable, detached host list, or {@code null} when inherited. An empty list explicitly removes host-specific exceptions.
     * @see Builder#trustingSSLHosts(String...)
     */
    @Nullable public List<String> getSslHostsToTrust() { return sslHostsToTrust; }
    /** @see Builder#trustingAllHosts(boolean) */
    @Nullable public Boolean getTrustAllHosts() { return trustAllHosts; }
    /** @see Builder#verifyingServerIdentity(boolean) */
    @Nullable public Boolean getVerifyingServerIdentity() { return verifyingServerIdentity; }
    /** @see Builder#withOpportunisticTLS(boolean) */
    @Nullable public Boolean getOpportunisticTLS() { return opportunisticTLS; }
    /**
     * @return The supplied class name, including when a supplied factory instance takes precedence, or {@code null} when absent.
     * @see Builder#withCustomSSLFactoryClass(String)
     */
    @Nullable public String getCustomSSLFactoryClass() { return customSSLFactoryClass; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearCustomSSLFactoryClass()
     */
    public boolean isCustomSSLFactoryClassCleared() { return customSSLFactoryClassCleared; }
    /**
     * @return The supplied, borrowed factory instance itself, or {@code null} when absent; inspection never creates a factory.
     * @see Builder#withCustomSSLFactoryInstance(SSLSocketFactory)
     */
    @Nullable public SSLSocketFactory getCustomSSLFactoryInstance() { return customSSLFactoryInstance; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearCustomSSLFactoryInstance()
     */
    public boolean isCustomSSLFactoryInstanceCleared() { return customSSLFactoryInstanceCleared; }

    /** Explicit choices for this group; inspection does not build or validate an incomplete configuration. */
    public static final class Builder {
        private List<String> sslHostsToTrust;
        private Boolean trustAllHosts;
        private Boolean verifyingServerIdentity;
        private Boolean opportunisticTLS;
        private String customSSLFactoryClass;
        private boolean customSSLFactoryClassCleared;
        private SSLSocketFactory customSSLFactoryInstance;
        private boolean customSSLFactoryInstanceCleared;
        private Builder() { }
        private Builder(final TlsConfig config) {
            sslHostsToTrust = config.sslHostsToTrust;
            trustAllHosts = config.trustAllHosts;
            verifyingServerIdentity = config.verifyingServerIdentity;
            opportunisticTLS = config.opportunisticTLS;
            customSSLFactoryClass = config.customSSLFactoryClass;
            customSSLFactoryClassCleared = config.customSSLFactoryClassCleared;
            customSSLFactoryInstance = config.customSSLFactoryInstance;
            customSSLFactoryInstanceCleared = config.customSSLFactoryInstanceCleared;
        }
        /**
         * Configures Angus Mail to trust certificates from the provided SMTP hosts without requiring their issuer to be present in the JVM trust store.
         * Server identity verification is a separate check and can be controlled with {@link #verifyingServerIdentity(boolean)}.
         * <p>
         * Passing an empty list removes the host-specific exception. With {@link #trustingAllHosts(boolean)} set to {@code false}, which is the default,
         * normal JVM trust-store validation then applies.
         * <p>
         * <strong>Security warning:</strong> This bypasses normal certificate-authority validation for the named hosts. Keep server identity verification
         * enabled, and prefer adding a private certificate authority to the JVM trust store when possible.
         * <p>
         * This method sets the transport-specific {@code mail.*.ssl.trust} property to a space-separated list of the provided {@code hosts}. If the
         * provided list is empty, the property is unset.
         * When trust-all is disabled, an exact {@code "*"} list also removes the exception; use {@link #trustingAllHosts(boolean)} to enable wildcard trust.
         * Replacing or removing an imported trust setting follows {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)}.
         *
         * @see <a href="https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html#mail.smtp.ssl.trust"><code>mail.smtp.ssl.trust</code></a>
         * @see #trustingAllHosts(boolean)
         * @see #verifyingServerIdentity(boolean)
         *
         * @param sslHostsToTrust See main description.
         */
        @NotNull public Builder trustingSSLHosts(final String... sslHostsToTrust) { this.sslHostsToTrust = new ArrayList<>(Arrays.asList(sslHostsToTrust.clone())); return this; }
        /**
         * Selects an empty host-exception list.
         * @see #trustingSSLHosts(String...)
         */
        @NotNull public Builder clearTrustedSSLHosts() { return trustingSSLHosts(); }
        /**
         * @return A detached, modifiable copy of the supplied hosts, or {@code null} when inherited. Editing the copy does not configure this builder.
         * @see #trustingSSLHosts(String...)
         */
        @Nullable public List<String> getSslHostsToTrust() { return sslHostsToTrust == null ? null : new ArrayList<>(sslHostsToTrust); }
        /**
         * Restores captured configuration for this member.
         * @see #trustingSSLHosts(String...)
         */
        @NotNull public Builder useConfiguredSslHostsToTrust() { sslHostsToTrust = null; return this; }
        /**
         * Controls whether Angus Mail trusts certificates from every SMTP host without requiring their issuer to be present in the JVM trust store.
         * Defaults to {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_TRUST_ALL_HOSTS}, so normal JVM trust-store validation applies unless this method or
         * {@link #trustingSSLHosts(String...)} enables an exception.
         * <p>
         * <strong>Security warning:</strong> Setting this to {@code true} disables certificate-authority trust validation for all SMTP hosts. Server identity
         * verification remains a separate check and should stay enabled, but it does not make trusting every certificate safe against an active attacker.
         * Prefer configuring the JVM trust store or, when that is not possible, a narrow exception through {@link #trustingSSLHosts(String...)}.
         * Conflicts with imported trust settings follow {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)}.
         *
         * @see <a href="https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html#mail.smtp.ssl.trust">mail.smtp.ssl.trust</a>
         * @see #trustingSSLHosts(String...)
         * @see #verifyingServerIdentity(boolean)
         *
         * @param trustAllHosts See main description.
         */
        @NotNull public Builder trustingAllHosts(final boolean trustAllHosts) { this.trustAllHosts = trustAllHosts; return this; }
        /** @see #trustingAllHosts(boolean) */
        @Nullable public Boolean getTrustAllHosts() { return trustAllHosts; }
        /**
         * Selects the published library fallback.
         * @see #trustingAllHosts(boolean)
         */
        @NotNull public Builder resetTrustingAllHosts() { return trustingAllHosts(MailerGenericBuilder.DEFAULT_TRUST_ALL_HOSTS); }
        /**
         * Restores captured configuration for this member.
         * @see #trustingAllHosts(boolean)
         */
        @NotNull public Builder useConfiguredTrustAllHosts() { trustAllHosts = null; return this; }
        /**
         * Controls whether Angus Mail verifies that the SMTP server certificate matches the host used for the connection. Defaults to
         * {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_VERIFY_SERVER_IDENTITY}, including for opportunistic TLS with {@link TransportStrategy#SMTP}.
         * <p>
         * Hostname verification and certificate-authority trust are independent checks. Normal secure TLS requires both a trusted certificate chain and a
         * matching server identity. Disabling this check is intended only for controlled compatibility or testing scenarios.
         * Conflicts with imported identity-verification settings follow {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)}.
         *
         * @see <a href="https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html#mail.smtp.ssl.checkserveridentity">mail.smtp.ssl.checkserveridentity</a>
         * @see #trustingAllHosts(boolean)
         * @see #trustingSSLHosts(String...)
         *
         * @param verifyingServerIdentity See main description.
         */
        @NotNull public Builder verifyingServerIdentity(final boolean verifyingServerIdentity) { this.verifyingServerIdentity = verifyingServerIdentity; return this; }
        /** @see #verifyingServerIdentity(boolean) */
        @Nullable public Boolean getVerifyingServerIdentity() { return verifyingServerIdentity; }
        /**
         * Selects the published library fallback.
         * @see #verifyingServerIdentity(boolean)
         */
        @NotNull public Builder resetVerifyingServerIdentity() { return verifyingServerIdentity(MailerGenericBuilder.DEFAULT_VERIFY_SERVER_IDENTITY); }
        /**
         * Restores captured configuration for this member.
         * @see #verifyingServerIdentity(boolean)
         */
        @NotNull public Builder useConfiguredVerifyingServerIdentity() { verifyingServerIdentity = null; return this; }
        /**
         * Controls whether plain {@link TransportStrategy#SMTP} attempts an optional STARTTLS upgrade when the server offers it.
         * It defaults to {@value MailerGenericBuilder#DEFAULT_OPPORTUNISTIC_TLS};
         * setting it to {@code false} is an escape hatch for SMTP servers that break during STARTTLS negotiation.
         * <p>
         * An unset choice inherits the Mailer's captured configuration; this value builder does not load or resolve that snapshot.
         * Calling this method supplies an explicit choice for a Mailer built with this group.
         * <p>
         * This setting is deliberately limited to plain SMTP. It has no effect on mandatory TLS in {@link TransportStrategy#SMTP_TLS}, OAuth2 transport, or
         * implicit TLS in {@link TransportStrategy#SMTPS}.
         * {@link #resetOpportunisticTLS()} restores the built-in default rather than the captured property; configuration locks still apply.
         * A differing explicit captured or raw choice follows {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)}.
         *
         * @param opportunisticTLS Whether plain SMTP should attempt STARTTLS when offered.
         */
        @NotNull public Builder withOpportunisticTLS(final boolean opportunisticTLS) { this.opportunisticTLS = opportunisticTLS; return this; }
        /** @see #withOpportunisticTLS(boolean) */
        @Nullable public Boolean getOpportunisticTLS() { return opportunisticTLS; }
        /**
         * Selects the published library fallback.
         * @see #withOpportunisticTLS(boolean)
         */
        @NotNull public Builder resetOpportunisticTLS() { return withOpportunisticTLS(MailerGenericBuilder.DEFAULT_OPPORTUNISTIC_TLS); }
        /**
         * Restores captured configuration for this member.
         * @see #withOpportunisticTLS(boolean)
         */
        @NotNull public Builder useConfiguredOpportunisticTLS() { opportunisticTLS = null; return this; }
        /**
         * Uses your SSL socket factory class, for example to supply a client certificate or a private trust store.
         * <p>
         * A supplied factory instance takes precedence. The class needs a public static {@code getDefault()} method returning an
         * {@link SSLSocketFactory}; initialization stays lazy until the factory is used. For a modular application using the managed Angus
         * provider, export its package to {@code org.simplejavamail.mailprovider.angus}, or supply an instance instead.
         * <p>
         * On an owned Angus Session, a failure in your factory stops connection setup by default instead of retrying with the default factory.
         * An explicit {@code mail.smtp.socketFactory.fallback=true} (or the {@code mail.smtps} equivalent) retains that advanced behavior,
         * but can abandon your factory's client identity or trust decisions. Failed TLS wrapping closes its connected socket before cleanup or fallback.
         * This does not add physical cancellation support to a custom factory. Caller-owned Sessions keep their own settings.
         * <p>
         * Sets {@code mail.smtp.ssl.socketFactory.class} or {@code mail.smtps.ssl.socketFactory.class}. A custom factory can bypass the provider's
         * SOCKS socket creation, so check authenticated-proxy usage with your factory.
         * <p>
         * Passing null or calling {@link #clearCustomSSLFactoryClass()} removes only the stored class name; a supplied instance stays in place.
         * Clearing does not restore a captured property value. Replacement or removal of an imported class name follows
         * {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)}, and configuration locks still apply.
         *
         * @param customSSLFactoryClass The fully qualified name of the factory class. Example: <code>javax.net.ssl.SSLSocketFactory</code>
         *
         * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html">Java / Jakarta Mail properties</a>
         * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
         */
        @NotNull public Builder withCustomSSLFactoryClass(final String customSSLFactoryClass) { this.customSSLFactoryClass = customSSLFactoryClass; customSSLFactoryClassCleared = customSSLFactoryClass == null; return this; }
        /**
         * @return The stored class name even when a supplied instance takes precedence, or {@code null} when absent.
         * @see #withCustomSSLFactoryClass(String)
         */
        @Nullable public String getCustomSSLFactoryClass() { return customSSLFactoryClass; }
        /**
         * Removes this choice rather than restoring captured configuration.
         * @see #withCustomSSLFactoryClass(String)
         */
        @NotNull public Builder clearCustomSSLFactoryClass() { customSSLFactoryClass = null; customSSLFactoryClassCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withCustomSSLFactoryClass(String)
         */
        @NotNull public Builder useConfiguredCustomSSLFactoryClass() { customSSLFactoryClass = null; customSSLFactoryClassCleared = false; return this; }
        /**
         * Uses your SSL socket factory instance, for example to supply a client certificate or a private trust store.
         * <p>
         * Takes precedence over a configured factory class. No reflective module access to your implementation package is needed.
         * For an owned Angus Session, omitted fallback is disabled: failure in your factory stops connection setup rather than silently
         * trying the default factory. Explicit transport-specific {@code socketFactory.fallback=true} remains available for deliberate fallback.
         * Failed TLS wrapping closes the connected socket, retaining the factory failure and suppressing any secondary close failure.
         * The owned Session holds a cleanup decorator; keep your original reference for implementation-specific factory operations.
         * <p>
         * Sets {@code mail.smtp.ssl.socketFactory} or {@code mail.smtps.ssl.socketFactory}. A custom factory can bypass the provider's SOCKS
         * socket creation, so check authenticated-proxy usage with your factory. This does not add physical cancellation support.
         * Caller-owned Sessions keep their own settings.
         * <p>
         * Passing null or calling {@link #clearCustomSSLFactoryInstance()} removes this instance so a configured factory class can be used again.
         * This never closes the supplied factory or removes the stored class name.
         * Conflicts with an imported raw factory instance follow {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)}.
         *
         * @param customSSLFactoryInstance An instance of the {@link SSLSocketFactory} class.
         *
         * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html">Java / Jakarta Mail properties</a>
         * @see #withCustomSSLFactoryClass(String)
         */
        @NotNull public Builder withCustomSSLFactoryInstance(final SSLSocketFactory customSSLFactoryInstance) { this.customSSLFactoryInstance = customSSLFactoryInstance; customSSLFactoryInstanceCleared = customSSLFactoryInstance == null; return this; }
        /**
         * @return The supplied, borrowed instance itself, or {@code null} when absent. No factory is constructed by inspection.
         * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
         */
        @Nullable public SSLSocketFactory getCustomSSLFactoryInstance() { return customSSLFactoryInstance; }
        /**
         * Removes this choice rather than restoring captured configuration.
         * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
         */
        @NotNull public Builder clearCustomSSLFactoryInstance() { customSSLFactoryInstance = null; customSSLFactoryInstanceCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
         */
        @NotNull public Builder useConfiguredCustomSSLFactoryInstance() { customSSLFactoryInstance = null; customSSLFactoryInstanceCleared = false; return this; }
        /** @return An immutable snapshot; feature validation remains with its owning runtime. */
        @NotNull public TlsConfig build() { return new TlsConfig(this); }
    }
}
