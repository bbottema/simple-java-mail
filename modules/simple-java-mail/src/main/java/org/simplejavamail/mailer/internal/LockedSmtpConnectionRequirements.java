package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge.LOOPBACK_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.CUSTOM_SSLFACTORY_CLASS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SESSION_TIMEOUT_MILLIS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TRUST_ALL_HOSTS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TRUSTED_HOSTS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_VERIFY_SERVER_IDENTITY;
import static org.simplejavamail.config.ConfigLoader.Property.OPPORTUNISTIC_TLS;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_SOCKS5BRIDGE_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_CLIENT_HOSTNAME;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_LOCAL_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_LOCAL_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_USERNAME;
import static org.simplejavamail.config.ConfigLoader.Property.TRANSPORT_STRATEGY;
import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;
import static org.simplejavamail.internal.util.StringUtil.escapeControlCharacters;

/** Pure connection restrictions shared by construction preflight and the completed Mailer's live Session checks. */
final class LockedSmtpConnectionRequirements {

	private final ConfigurationLocks locks;
	private final Map<String, Property> sessionProperties = new LinkedHashMap<>();
	private final Map<String, Object> requiredSessionValues = new LinkedHashMap<>();
	private final Map<String, Object> lockedExtraProperties = new LinkedHashMap<>();
	private final ProxyConfig proxy;
	private final boolean trustAllSSLHost;
	@Nullable private final String trustProperty;

	LockedSmtpConnectionRequirements(final SimpleJavaMailConfig config, final ProxyConfig proxy,
			final boolean trustAllSSLHost, @Nullable final TransportStrategy strategy) {
		this.locks = locksOf(config);
		this.proxy = proxy;
		this.trustAllSSLHost = trustAllSSLHost;
		this.trustProperty = strategy == null ? null : strategy.propertyNameSSLTrust();
		if (strategy != null && !locks.isEmpty()) {
			mapSessionProperties(config, strategy);
		}
		for (Map.Entry<String, Object> entry : locks.getValues().entrySet()) {
			if (entry.getKey().startsWith("simplejavamail.extraproperties.")) {
				final String key = entry.getKey().substring("simplejavamail.extraproperties.".length());
				if (requiredSessionValues.containsKey(key) && !sameSessionValue(key, requiredSessionValues.get(key), entry.getValue())) {
					throw locks.conflict(entry.getKey(), "This locked Jakarta Mail property and "
							+ ConfigurationLocks.lockedName(sessionProperties.get(key).key()) + " configure the same SMTP setting, but their values differ. "
							+ "Make the two locked values agree, or remove one of these locks from the configuration source.");
				}
				requiredSessionValues.put(key, entry.getValue());
				// A literal raw lock owns this physical requirement, including values derived from an authenticated bridge.
				sessionProperties.remove(key);
				lockedExtraProperties.put(key, entry.getValue());
			}
		}
	}

	/** Locked low-level values replace automatic defaults, but not an explicitly contradictory builder call. */
	static void verifyBuilderCustomizations(final ConfigurationLocks locks, final Map<Property, Object> supplied,
			@Nullable final TransportStrategy strategy) {
		if (strategy == null) {
			return;
		}
		for (Map.Entry<Property, Object> entry : supplied.entrySet()) {
			if (entry.getKey() == DEFAULT_TRUST_ALL_HOSTS || entry.getKey() == DEFAULT_TRUSTED_HOSTS) {
				// These choices cooperate; check their final projected value after connection resolution.
				continue;
			}
			if (entry.getKey() == TRANSPORT_STRATEGY) {
				transportRequirements((TransportStrategy) entry.getValue()).forEach((key, value) -> verifyExtraCustomization(locks, key.toString(), value));
			} else {
				for (String key : sessionKeys(entry.getKey(), strategy)) {
					verifyExtraCustomization(locks, key, entry.getValue());
				}
			}
		}
	}

	private static void verifyExtraCustomization(final ConfigurationLocks locks, final String sessionKey, @Nullable final Object value) {
		final String property = "simplejavamail.extraproperties." + sessionKey;
		if (locks.getValues().containsKey(property) && !sameSessionValue(sessionKey, value, locks.getValues().get(property))) {
			throw locks.conflict(property, "A Mailer builder call changes the SMTP setting controlled by this locked Jakarta Mail property. "
					+ "Remove the conflicting builder call, set it to the locked value, or change this lock in its configuration source.");
		}
	}

	private static List<String> sessionKeys(final Property property, final TransportStrategy strategy) {
		switch (property) {
			case SMTP_HOST: return List.of(strategy.propertyNameHost());
			case SMTP_PORT: return List.of(strategy.propertyNamePort());
			case SMTP_USERNAME: return List.of(strategy.propertyNameUsername());
			case SMTP_LOCAL_ADDRESS: return List.of(strategy.propertyNameLocalAddress());
			case SMTP_LOCAL_PORT: return List.of(strategy.propertyNameLocalPort());
			case SMTP_CLIENT_HOSTNAME: return List.of(strategy.propertyNameLocalHost());
			case DEFAULT_SESSION_TIMEOUT_MILLIS:
				return List.of(strategy.propertyNameConnectionTimeout(), strategy.propertyNameTimeout(), strategy.propertyNameWriteTimeout());
			case DEFAULT_CONNECT_TIMEOUT_MILLIS: return List.of(strategy.propertyNameConnectionTimeout());
			case DEFAULT_READ_TIMEOUT_MILLIS: return List.of(strategy.propertyNameTimeout());
			case DEFAULT_WRITE_TIMEOUT_MILLIS: return List.of(strategy.propertyNameWriteTimeout());
			case DEFAULT_VERIFY_SERVER_IDENTITY: return List.of(strategy.propertyNameCheckServerIdentity());
			case DEFAULT_TRUST_ALL_HOSTS:
			case DEFAULT_TRUSTED_HOSTS: return List.of(strategy.propertyNameSSLTrust());
			case CUSTOM_SSLFACTORY_CLASS: return List.of(strategy.propertyNameSSLSocketFactoryClass());
			case OPPORTUNISTIC_TLS: return strategy == TransportStrategy.SMTP ? List.of("mail.smtp.starttls.enable") : List.of();
			default: return List.of();
		}
	}

	boolean hasLocks() {
		return !locks.isEmpty();
	}

	private void mapSessionProperties(final SimpleJavaMailConfig config, final TransportStrategy strategy) {
		map(config, SMTP_HOST, strategy.propertyNameHost());
		map(config, SMTP_PORT, strategy.propertyNamePort());
		map(config, SMTP_USERNAME, strategy.propertyNameUsername());
		map(config, SMTP_LOCAL_ADDRESS, strategy.propertyNameLocalAddress());
		map(config, SMTP_LOCAL_PORT, strategy.propertyNameLocalPort());
		map(config, SMTP_CLIENT_HOSTNAME, strategy.propertyNameLocalHost());
		map(config, DEFAULT_SESSION_TIMEOUT_MILLIS, strategy.propertyNameConnectionTimeout());
		map(config, DEFAULT_SESSION_TIMEOUT_MILLIS, strategy.propertyNameTimeout());
		map(config, DEFAULT_SESSION_TIMEOUT_MILLIS, strategy.propertyNameWriteTimeout());
		mapSocketTimeout(config, Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, strategy.propertyNameConnectionTimeout());
		mapSocketTimeout(config, Property.DEFAULT_READ_TIMEOUT_MILLIS, strategy.propertyNameTimeout());
		mapSocketTimeout(config, Property.DEFAULT_WRITE_TIMEOUT_MILLIS, strategy.propertyNameWriteTimeout());
		map(config, DEFAULT_VERIFY_SERVER_IDENTITY, strategy.propertyNameCheckServerIdentity());
		map(config, CUSTOM_SSLFACTORY_CLASS, strategy.propertyNameSSLSocketFactoryClass());
		if (locks.contains(SMTP_PASSWORD)) {
			if (OAuth2AccessTokenResolver.isOAuth2Transport(strategy)) {
				put(SMTP_PASSWORD, TransportStrategy.OAUTH2_TOKEN_PROPERTY, config.get(ConfigKeys.SMTP_PASSWORD));
			} else {
				put(SMTP_PASSWORD, strategy.propertyNameAuthenticate(), true);
			}
		}
		mapProxyConnection(strategy);
		if (locks.contains(TRANSPORT_STRATEGY)) {
			final TransportStrategy lockedStrategy = config.get(ConfigKeys.TRANSPORT_STRATEGY);
			transportRequirements(lockedStrategy).forEach((key, value) -> put(TRANSPORT_STRATEGY, key.toString(), value));
		}
		if (locks.contains(OPPORTUNISTIC_TLS) && strategy == TransportStrategy.SMTP) {
			put(OPPORTUNISTIC_TLS, "mail.smtp.starttls.enable", config.get(ConfigKeys.OPPORTUNISTIC_TLS));
		}
		if (Boolean.TRUE.equals(locks.getValues().get(DEFAULT_TRUST_ALL_HOSTS.key())) || locks.contains(DEFAULT_TRUSTED_HOSTS)) {
			final boolean trustAll = trustAllSSLHost;
			final String trusted = config.get(ConfigKeys.DEFAULT_TRUSTED_HOSTS);
			if (trustAll && locks.contains(DEFAULT_TRUSTED_HOSTS) && !"*".equals(trusted)) {
				throw locks.conflict(DEFAULT_TRUSTED_HOSTS, "The SMTP configuration trusts every server certificate, but this lock requires the trusted-host list. "
						+ "Use trustingAllHosts(false) and remove any trust-all property, "
						+ "or change the conflicting trust settings in the configuration source.");
			}
			put(locks.contains(DEFAULT_TRUST_ALL_HOSTS) ? DEFAULT_TRUST_ALL_HOSTS : DEFAULT_TRUSTED_HOSTS,
					strategy.propertyNameSSLTrust(), trustAll ? "*"
							: ConnectionPropertyValues.boundedTrustValue(trusted == null ? null : String.join(" ", trusted.split(";"))));
		}
	}

	/** Strategy defaults are not all restrictions: SMTP's opportunistic TLS and SMTPS's QUIT waiting remain separately customizable. */
	private static Properties transportRequirements(final TransportStrategy strategy) {
		final Properties requirements = strategy.generateProperties();
		// The SJM marker identifies a construction choice; the actual SMTP settings, not that marker, must satisfy the lock.
		requirements.remove(TRANSPORT_STRATEGY.key());
		if (strategy == TransportStrategy.SMTP) {
			requirements.remove("mail.smtp.starttls.enable");
			requirements.remove("mail.smtp.starttls.required");
		}
		requirements.remove("mail.smtps.quitwait");
		return requirements;
	}

	private void mapProxyConnection(final TransportStrategy strategy) {
		if (!proxy.requiresProxy()) {
			return;
		}
		final String hostKey = strategy.propertyNameSocksHost();
		final String portKey = strategy.propertyNameSocksPort();
		if (locks.contains(PROXY_HOST) || locks.getValues().containsKey("simplejavamail.extraproperties." + hostKey)) {
			put(PROXY_HOST, strategy.propertyNameSocksHost(), proxy.requiresAuthentication()
					? LOOPBACK_HOST : proxy.getRemoteProxyHost());
		}
		// An authenticated bridge may bind an ephemeral local port. Its fixed logical destination is checked against the registration instead.
		if ((locks.contains(PROXY_PORT) || locks.getValues().containsKey("simplejavamail.extraproperties." + portKey)) && !proxy.requiresAuthentication()) {
			put(PROXY_PORT, strategy.propertyNameSocksPort(), proxy.getRemoteProxyPort());
		}
		if (proxy.requiresAuthentication() && locks.getValues().containsKey("simplejavamail.extraproperties." + portKey)) {
			if (proxy.getProxyBridgePort() == null || proxy.getProxyBridgePort() == 0) {
				throw locks.conflict("simplejavamail.extraproperties." + portKey,
						"This literal Session port is locked, but the authenticated proxy bridge chooses its port when it starts. "
								+ "Configure a compatible fixed bridge port, or lock the remote proxy port through simplejavamail.locked.proxy.port instead.");
			}
			put(PROXY_SOCKS5BRIDGE_PORT, portKey, proxy.getProxyBridgePort());
		}
	}

	static void verifyCustomSocketFactory(final ConfigurationLocks locks, final Properties resolvedProperties,
			@Nullable final TransportStrategy strategy) {
		if (strategy != null && locks.contains(CUSTOM_SSLFACTORY_CLASS)
				&& resolvedProperties.containsKey(strategy.propertyNameSSLSocketFactory())) {
			throw locks.conflict(CUSTOM_SSLFACTORY_CLASS, "An SSL factory instance replaces the factory class selected by this lock. "
					+ "Remove the instance choice or change the class lock in its configuration source.");
		}
		if (strategy == null || !hasCustomSocketFactory(resolvedProperties, strategy)) {
			return;
		}
		for (Property property : new Property[]{DEFAULT_TRUST_ALL_HOSTS, DEFAULT_TRUSTED_HOSTS, DEFAULT_VERIFY_SERVER_IDENTITY}) {
			if (locks.contains(property)) {
				throw locks.conflict(property, "A caller-supplied socket factory controls TLS connections, but this configuration also locks certificate trust "
						+ "or server-identity settings. Simple Java Mail cannot verify that the custom factory honors those locks. "
						+ "Remove the custom socket factory so the managed transport can apply the configured TLS checks, "
						+ "or remove the locked properties for certificate trust and server-identity checking and let your custom factory handle them.");
			}
		}
	}

	/** A plain socket factory can also return TLS sockets, so both Jakarta Mail factory slots need the ownership check. */
	static boolean hasCustomSocketFactory(final Properties properties, final TransportStrategy strategy) {
		final String sslFactory = strategy.propertyNameSSLSocketFactory();
		final String plainFactory = sslFactory.replace(".ssl.", ".");
		// Instance slots use direct objects; class names also remain active through provider-visible inherited Strings.
		return properties.containsKey(sslFactory) || ConnectionPropertyValues.visibleValue(properties, sslFactory + ".class") != null
				|| properties.containsKey(plainFactory) || ConnectionPropertyValues.visibleValue(properties, plainFactory + ".class") != null;
	}

	private void map(final SimpleJavaMailConfig config, final Property property, final String sessionKey) {
		if (locks.contains(property)) {
			put(property, sessionKey, config.asMap().get(property));
		}
	}

	private void mapSocketTimeout(final SimpleJavaMailConfig config, final Property property, final String sessionKey) {
		if (!locks.contains(property)) {
			return;
		}
		final Object value = config.asMap().get(property);
		if (requiredSessionValues.containsKey(sessionKey) && !sameSessionValue(sessionKey, requiredSessionValues.get(sessionKey), value)) {
			throw locks.conflict(property, "This lock and " + ConfigurationLocks.lockedName(sessionProperties.get(sessionKey).key())
					+ " configure the same SMTP setting with different values. Make the locked values agree or remove one of these locks from the configuration source.");
		}
		put(property, sessionKey, value);
	}

	private void put(final Property property, final String sessionKey, @Nullable final Object value) {
		sessionProperties.put(sessionKey, property);
		requiredSessionValues.put(sessionKey, value);
	}

	/** Check explicit low-level overrides before initialization would otherwise replace them. */
	void verifyAdditionalProperties(final Properties properties) {
		verifyExplicitConnectionChoices(properties);
	}

	void verifyExplicitConnectionChoices(final Map<?, ?> properties) {
		// These are literal declarations. Defaults inherited by additional Properties are not copied into the Session.
		if (trustProperty != null && Boolean.FALSE.equals(locks.getValues().get(DEFAULT_TRUST_ALL_HOSTS.key()))
				&& "*".equals(properties.get(trustProperty))) {
			throw locks.conflict(DEFAULT_TRUST_ALL_HOSTS, "An explicit SMTP trust property enables wildcard trust, but this lock disables it. Remove the conflicting customization.");
		}
		for (String key : requiredSessionValues.keySet()) {
			if (properties.containsKey(key)) {
				// Ordinary SOCKS declarations describe the remote route. Locked raw properties instead constrain the literal local route.
				if (proxy.requiresAuthentication() && !lockedExtraProperties.containsKey(key)
						&& sessionProperties.get(key) == PROXY_HOST && Objects.equals(properties.get(key), proxy.getRemoteProxyHost())) {
					continue;
				}
				verifySessionValue(key, properties.get(key));
			}
		}
	}

	void verifyCallerOwnedSession(final Session session) {
		final TransportStrategy strategy = MailerSessionSettings.transportStrategy(session.getProperties());
		verifyCallerOwnedCredentials();
		if (strategy == null) {
			verifyUnknownSessionStrategy();
		}
		lockedExtraProperties.keySet().stream().filter(session.getProperties()::containsKey)
				.forEach(key -> verifySessionValue(key, ConnectionPropertyValues.visibleValue(session.getProperties(), key)));
		// These settings belong to the supplied Session, not to the generic builder that initializes operational settings below.
		for (Map.Entry<String, Property> entry : sessionProperties.entrySet()) {
			if (entry.getValue() == SMTP_HOST || entry.getValue() == SMTP_PORT || entry.getValue() == SMTP_USERNAME
					|| entry.getValue() == TRANSPORT_STRATEGY || entry.getValue() == CUSTOM_SSLFACTORY_CLASS || entry.getValue() == OPPORTUNISTIC_TLS) {
				verifySessionValue(entry.getKey(), ConnectionPropertyValues.visibleValue(session.getProperties(), entry.getKey()));
			}
		}
	}

	private void verifyCallerOwnedCredentials() {
		final Property property = locks.contains(SMTP_PASSWORD) ? SMTP_PASSWORD : locks.contains(SMTP_USERNAME) ? SMTP_USERNAME : null;
		if (property == null) {
			return;
		}
		final String setting = property == SMTP_USERNAME ? "username" : "credential";
		throw locks.conflict(property, "The factory locks the SMTP " + setting + ", but you supplied a Session that controls authentication. "
				+ "Its cached credentials or Authenticator can replace the username and password used when connecting, even if the visible Session properties match. "
				+ "Simple Java Mail cannot inspect or replace that Authenticator, so it cannot verify that the locked " + setting + " will be used. "
				+ "Call this factory's mailerBuilder() without a Session, or remove the SMTP " + setting
				+ " lock from the configuration source if your Session should control authentication.");
	}

	private void verifyUnknownSessionStrategy() {
		for (Property property : Property.values()) {
			if (locks.contains(property) && (property == TRANSPORT_STRATEGY || property.name().startsWith("PROXY_")
					|| !sessionKeys(property, TransportStrategy.SMTP).isEmpty())) {
				throw locks.conflict(property, "You supplied a Session whose mail.transport.protocol is neither smtp nor smtps, while this factory locks an SMTP setting. "
						+ "Simple Java Mail cannot map that custom protocol's connection properties to this SMTP lock reliably. "
						+ "Call this factory's mailerBuilder() without a Session, or remove this lock from the configuration source.");
			}
		}
	}

	/** Checks literal Session requirements without constructing operational resources or inspecting hidden provider state. */
	void verifySession(final Session session) {
		if (locks.isEmpty()) {
			return;
		}
		for (String key : requiredSessionValues.keySet()) {
			verifySessionValue(key, ConnectionPropertyValues.visibleValue(session.getProperties(), key));
		}
		if (Boolean.FALSE.equals(locks.getValues().get(DEFAULT_TRUST_ALL_HOSTS.key()))) {
			final TransportStrategy strategy = MailerSessionSettings.transportStrategy(session.getProperties());
			if (strategy != null && "*".equals(session.getProperty(strategy.propertyNameSSLTrust()))) {
				throw locks.conflict(DEFAULT_TRUST_ALL_HOSTS, "The selected SMTP Session trusts every server certificate, but this locked setting disables trust-all. "
						+ "Remove the trust-all customization from the selected SMTP configuration, or change this lock in its configuration source.");
			}
		}
	}
	private void verifySessionValue(final String key, @Nullable final Object actual) {
		final Object expected = requiredSessionValues.get(key);
		// Disabling opportunistic TLS removes this property on owned Sessions; Jakarta Mail's absent-value behavior is false.
		if (sessionProperties.get(key) == OPPORTUNISTIC_TLS && Boolean.FALSE.equals(expected) && actual == null) {
			return;
		}
		if (!sameSessionValue(key, expected, actual)) {
			final Property property = sessionProperties.get(key);
			throw locks.conflict(property == null ? "simplejavamail.extraproperties." + key : property.key(),
					"The supplied or selected SMTP configuration "
							+ (actual == null ? "is missing Jakarta Mail property " : "has a different value for Jakarta Mail property ")
							+ escapeControlCharacters(key) + ", so it does not satisfy this lock. "
							+ "Let this factory create the Session, or correct the supplied Session, additional properties, or selected pool member to agree with the lock. "
							+ "If that restriction is not intended, change the lock in its configuration source.");
		}
	}

	private static boolean sameSessionValue(final String key, @Nullable final Object expected, @Nullable final Object actual) {
		if (ConnectionPropertyValues.isSupported(key)) {
			return ConnectionPropertyValues.equivalent(key, expected, actual);
		}
		if (actual == null) {
			return expected == null;
		}
		if (expected instanceof Boolean) {
			return expected.toString().equalsIgnoreCase(actual.toString());
		} else if (expected instanceof Integer) {
			return integerMatches((Integer) expected, actual);
		} else {
			return Objects.equals(
					expected == null ? null : expected.toString(),
					actual == null ? null : actual.toString());
		}
	}

	private static boolean integerMatches(final Integer expected, @Nullable final Object actual) {
		try {
			return expected.equals(Integer.valueOf(String.valueOf(actual)));
		} catch (NumberFormatException invalid) {
			return false;
		}
	}
}
