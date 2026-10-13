package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;

import java.time.Duration;
import java.util.Map;

import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_ON_AUTHENTICATION_FAILURE;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_CONNECTION_ATTEMPTS;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_CONNECTION_RETRY_DELAY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_RECHECK_BACKOFF_MULTIPLIER;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_RECHECK_INTERVAL;

/** Resolves the same policy for property-backed and generated CLI configuration; it owns no Mailers or resources. */
final class ConfiguredFailover {

	private ConfiguredFailover() { }

	@Nullable
	static FailoverPolicy policy(final SimpleJavaMailConfig config) {
		if (!config.hasProperty(Property.FAILOVER_MAILER)) {
			rejectPolicyWithoutFailoverDestination(config);
			return null;
		}
		final Duration initialRecheckInterval = config.valueOrProperty(null, Property.FAILOVER_PRIMARY_RECHECK_INTERVAL, DEFAULT_FAILOVER_PRIMARY_RECHECK_INTERVAL);
		final int recheckBackoffMultiplier = config.valueOrProperty(null, Property.FAILOVER_PRIMARY_BACKOFF_MULTIPLIER,
				DEFAULT_FAILOVER_PRIMARY_RECHECK_BACKOFF_MULTIPLIER);
		verifyRecheckBackoffHasMaximum(config, recheckBackoffMultiplier);
		return new FailoverPolicy(config.valueOrProperty(null, Property.FAILOVER_PRIMARY_CONNECTION_ATTEMPTS, DEFAULT_FAILOVER_PRIMARY_CONNECTION_ATTEMPTS),
				config.valueOrProperty(null, Property.FAILOVER_PRIMARY_RETRY_DELAY, DEFAULT_FAILOVER_PRIMARY_CONNECTION_RETRY_DELAY), initialRecheckInterval,
				recheckBackoffMultiplier, config.valueOrProperty(null, Property.FAILOVER_PRIMARY_MAXIMUM_RECHECK_INTERVAL, initialRecheckInterval),
				config.valueOrProperty(null, Property.FAILOVER_ON_AUTHENTICATION_FAILURE, DEFAULT_FAILOVER_ON_AUTHENTICATION_FAILURE),
				config.valueOrProperty(null, Property.FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE, DEFAULT_FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE));
	}

	private static void rejectPolicyWithoutFailoverDestination(final SimpleJavaMailConfig config) {
		if (config.asMap().keySet().stream().anyMatch(ConfiguredFailover::isPolicyProperty)) {
			throw new IllegalArgumentException("Failover policy properties need simplejavamail.failover.mailer as well. "
					+ "Name the destination to use after a connection failure, or remove the unused failover policy properties.");
		}
	}

	private static void verifyRecheckBackoffHasMaximum(final SimpleJavaMailConfig config, final int recheckBackoffMultiplier) {
		if (recheckBackoffMultiplier > 1 && !config.hasProperty(Property.FAILOVER_PRIMARY_MAXIMUM_RECHECK_INTERVAL)) {
			throw new IllegalArgumentException("Increasing failover backoff needs simplejavamail.failover.primary.maximumrecheckinterval. "
					+ "Supply a positive ISO-8601 duration, or use a backoff multiplier of one for a fixed interval.");
		}
	}

	static void verifyPolicyLocks(final ConfigurationLocks locks, @Nullable final FailoverPolicy policy) {
		final Map<Property, Object> policyValues = policy == null ? Map.of() : policyValues(policy);
		for (Property property : Property.values()) {
			if (isPolicyProperty(property)) {
				locks.verify(property, policyValues.get(property));
			}
		}
	}

	private static boolean isPolicyProperty(final Property property) {
		return property != Property.FAILOVER_MAILER && property.key().startsWith("simplejavamail.failover.");
	}

	private static Map<Property, Object> policyValues(final FailoverPolicy policy) {
		return Map.of(Property.FAILOVER_PRIMARY_CONNECTION_ATTEMPTS, policy.getPrimaryConnectionAttempts(),
				Property.FAILOVER_PRIMARY_RETRY_DELAY, policy.getPrimaryConnectionRetryDelay(),
				Property.FAILOVER_PRIMARY_RECHECK_INTERVAL, policy.getPrimaryRecheckInterval(),
				Property.FAILOVER_PRIMARY_BACKOFF_MULTIPLIER, policy.getPrimaryRecheckBackoffMultiplier(),
				Property.FAILOVER_PRIMARY_MAXIMUM_RECHECK_INTERVAL, policy.getPrimaryMaximumRecheckInterval(),
				Property.FAILOVER_ON_AUTHENTICATION_FAILURE, policy.isFailoverOnAuthenticationFailure(),
				Property.FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE, policy.isFailoverOnCertificateVerificationFailure());
	}
}
