package org.simplejavamail.api.mailer.config;

import lombok.Value;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.Mailer;
import static java.util.Objects.requireNonNull;

/**
 * Immutable supplied SocksProxyConfig choices. Unset fields use applicable raw endpoints and factory configuration when the Mailer is built.
 * Construction and inspection never start workers, connect, resolve configuration sources or own borrowed resources.
 * Replacing this group on a Mailer discards its previous supplied choices.
 * An untouched proxy group on a supplied Session preserves that Session's route and omitted settings.
 * Connection conflicts are resolved when the Mailer is built, under
 * {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)}. Repeated local setters retain ordinary replacement behavior.
 */
@Value
public class SocksProxyConfig {
    @Nullable String host;
    boolean hostCleared;
    @Nullable Integer port;
    boolean portCleared;
    @Nullable String username;
    boolean usernameCleared;
    @Nullable @ToString.Exclude String password;
    boolean passwordCleared;
    @Nullable Integer bridgePort;

    private SocksProxyConfig(final Builder builder) {
        host = builder.host;
        hostCleared = builder.hostCleared;
        port = builder.port;
        portCleared = builder.portCleared;
        username = builder.username;
        usernameCleared = builder.usernameCleared;
        password = builder.password;
        passwordCleared = builder.passwordCleared;
        bridgePort = builder.bridgePort;
    }

    /** @return A builder containing no supplied choices. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining supplied choices and borrowed-resource identities. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /** @see Builder#withHost(String) */
    @Nullable public String getHost() { return host; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearHost()
     */
    public boolean isHostCleared() { return hostCleared; }
    /** @see Builder#withPort(Integer) */
    @Nullable public Integer getPort() { return port; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearPort()
     */
    public boolean isPortCleared() { return portCleared; }
    /** @see Builder#withUsername(String) */
    @Nullable public String getUsername() { return username; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearUsername()
     */
    public boolean isUsernameCleared() { return usernameCleared; }
    /** @see Builder#withPassword(String) */
    @Nullable public String getPassword() { return password; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearPassword()
     */
    public boolean isPasswordCleared() { return passwordCleared; }
    /** @see Builder#withBridgePort(Integer) */
    @Nullable public Integer getBridgePort() { return bridgePort; }

    /** Explicit choices for this group; inspection does not build or validate an incomplete configuration. */
    public static final class Builder {
        private String host;
        private boolean hostCleared;
        private Integer port;
        private boolean portCleared;
        private String username;
        private boolean usernameCleared;
        private String password;
        private boolean passwordCleared;
        private Integer bridgePort;
        private Builder() { }
        private Builder(final SocksProxyConfig config) {
            host = config.host;
            hostCleared = config.hostCleared;
            port = config.port;
            portCleared = config.portCleared;
            username = config.username;
            usernameCleared = config.usernameCleared;
            password = config.password;
            passwordCleared = config.passwordCleared;
            bridgePort = config.bridgePort;
        }
        /**
         * Supplies the optional remote proxy host. A repeated call replaces the previous local choice.
         * Replacing or removing an imported host follows {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)}:
         * strict mode rejects conflicts; override mode gives this typed member precedence over its raw SOCKS counterpart.
         * @param host Proxy host, or {@code null} to explicitly remove a captured host; an untouched member uses applicable raw or factory configuration.
         */
        @NotNull public Builder withHost(final String host) { this.host = host; hostCleared = host == null; return this; }
        /** @see #withHost(String) */
        @Nullable public String getHost() { return host; }
        /**
         * Removes this choice rather than restoring captured configuration.
         * @see #withHost(String)
         */
        @NotNull public Builder clearHost() { host = null; hostCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withHost(String)
         */
        @NotNull public Builder useConfiguredHost() { host = null; hostCleared = false; return this; }
        /**
         * Supplies the remote proxy port. A repeated call replaces the previous local choice.
         * Replacing or removing an imported port follows {@link MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)}:
         * strict mode rejects conflicts; override mode gives this typed member precedence over its raw SOCKS counterpart.
         * <p>
         * Defaults to {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_PROXY_PORT} when an active proxy route has no configured or raw port.
         * @param port Proxy port, or {@code null} to explicitly remove a captured port; an untouched member uses applicable raw or factory configuration.
         */
        @NotNull public Builder withPort(final Integer port) { this.port = port; portCleared = port == null; return this; }
        /** @see #withPort(Integer) */
        @Nullable public Integer getPort() { return port; }
        /**
         * Removes this choice rather than restoring captured configuration.
         * @see #withPort(Integer)
         */
        @NotNull public Builder clearPort() { port = null; portCleared = true; return this; }
        /**
         * Selects the published library fallback.
         * @see #withPort(Integer)
         */
        @NotNull public Builder resetPort() { return withPort(MailerGenericBuilder.DEFAULT_PROXY_PORT); }
        /**
         * Restores captured configuration for this member.
         * @see #withPort(Integer)
         */
        @NotNull public Builder useConfiguredPort() { port = null; portCleared = false; return this; }
        /**
         * Sets the optional username to authenticate with the proxy. If set, Simple Java Mail will use its built in proxy bridge to
         * perform the SOCKS authentication, as the underlying JavaMail framework doesn't support this directly. The execution path
         * then will be:
         * <p>
         * {@code Simple Java Mail client -> JavaMail -> anonymous authentication with local proxy bridge -> full authentication with remote SOCKS proxy -> SMTP server}.
         * @param username Proxy username, or {@code null} to explicitly remove a captured username; an untouched member inherits the captured configuration.
         */
        @NotNull public Builder withUsername(final String username) { this.username = username; usernameCleared = username == null; return this; }
        /** @see #withUsername(String) */
        @Nullable public String getUsername() { return username; }
        /**
         * Removes this choice rather than restoring captured configuration.
         * @see #withUsername(String)
         */
        @NotNull public Builder clearUsername() { username = null; usernameCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withUsername(String)
         */
        @NotNull public Builder useConfiguredUsername() { username = null; usernameCleared = false; return this; }
        /**
         * Sets the optional password to authenticate with the proxy.
         * <p>
         * <strong>Note:</strong> this is only works in combination with the {@value org.simplejavamail.internal.modules.AuthenticatedSocksModule#NAME}.
         *
         * @see #withUsername(String)
         * @param password Proxy password, or {@code null} to explicitly remove a captured password; an untouched member inherits the captured configuration.
         */
        @NotNull public Builder withPassword(final String password) { this.password = password; passwordCleared = password == null; return this; }
        /** @see #withPassword(String) */
        @Nullable public String getPassword() { return password; }
        /**
         * Removes this choice rather than restoring captured configuration.
         * @see #withPassword(String)
         */
        @NotNull public Builder clearPassword() { password = null; passwordCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withPassword(String)
         */
        @NotNull public Builder useConfiguredPassword() { password = null; passwordCleared = false; return this; }
        /**
         * Relevant only when using username authentication with a proxy.
         * <p>
         * Overrides the port used by the temporary SOCKS5 relay between Jakarta Mail and the authenticated remote proxy. The relay binds to the JVM's
         * loopback address and owns this port while this Mailer has active proxy operations.
         * <p>
         * Port {@code 0} lets the operating system choose an available port every time the bridge starts. This avoids collisions between separate
         * authenticated-proxy Mailers. Use a positive port when an application needs the bridge to bind to a fixed port. Anonymous proxy connections do
         * not use a bridge port.
         * <p>
         * Defaults to automatic allocation ({@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_PROXY_BRIDGE_PORT}) if no custom default property was configured.
         * <p>
         * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.AuthenticatedSocksModule#NAME}.
         *
         * @param bridgePort The loopback port to use, or {@code 0} to allocate an available port automatically.
         *
         * @see #withUsername(String)
         */
        @NotNull public Builder withBridgePort(final Integer bridgePort) { this.bridgePort = requireNonNull(bridgePort, "bridgePort"); return this; }
        /** @see #withBridgePort(Integer) */
        @Nullable public Integer getBridgePort() { return bridgePort; }
        /**
         * Selects the published library fallback.
         * @see #withBridgePort(Integer)
         */
        @NotNull public Builder resetBridgePort() { return withBridgePort(MailerGenericBuilder.DEFAULT_PROXY_BRIDGE_PORT); }
        /**
         * Restores captured configuration for this member.
         * @see #withBridgePort(Integer)
         */
        @NotNull public Builder useConfiguredBridgePort() { bridgePort = null; return this; }
        /**
         * Removes the proxy endpoint and credentials, selecting the built-in bridge-port fallback.
         * @see #withHost(String)
         */
        @NotNull public Builder clearProxy() { return clearHost().clearPort().clearUsername().clearPassword().resetBridgePort(); }
        /** Describes choices without disclosing proxy credentials. */
        @Override public String toString() { return "SocksProxyConfig.Builder"; }
        /** @return An immutable snapshot; feature validation remains with its owning runtime. */
        @NotNull public SocksProxyConfig build() { return new SocksProxyConfig(this); }
    }
}
