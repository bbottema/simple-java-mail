package org.simplejavamail.internal.config;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.config.ConfigLoader.Property;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/** Internal property-name grammar shared by source loading and process-boundary adapters. */
@ApiStatus.Internal
public final class ConfigurationPropertyNames {

	/** Recognizes extra properties loaded directly into the Jakarta Mail Session under their original suffix. */
	public static final Pattern EXTRA_PROPERTY_PATTERN = compile("^simplejavamail\\.extraproperties\\.(?<actualProperty>.*)");
	public static final Pattern CONNECTIONPOOL_CLUSTER_PROPERTY_PATTERN = compile(
			"^simplejavamail\\.defaults\\.connectionpool\\.clusters\\.(?<clusterAlias>[^.]+)\\.(?<clusterProperty>"
					+ "clusterkey\\.uuid|coresize|maxsize|claimtimeout\\.millis|expireafter\\.millis|expireaftercreation\\.millis|loadbalancing\\.strategy)$");
	public static final Pattern NAMED_MAILER_PROPERTY_PATTERN = compile("^simplejavamail\\.mailers\\.(?<name>[A-Za-z0-9_-]+)\\.(?<tail>.+)$");

	private ConfigurationPropertyNames() {
	}

	public static boolean isRecognizedKey(@Nullable final String key) {
		if (key == null) {
			return false;
		}
		final String ordinaryKey = ConfigurationLocks.ordinaryName(key);
		for (Property property : Property.values()) {
			if (property != Property.EXTRA_PROPERTIES
					&& property != Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS
					&& property != Property.NAMED_MAILERS
					&& property.key().equals(ordinaryKey)) {
				return true;
			}
		}
		return isWildcardKey(ordinaryKey);
	}

	/** Accepts an ordinary canonical name, with any outer locked namespace already removed. */
	public static boolean isWildcardKey(final String key) {
		final Matcher namedMailerProperty = NAMED_MAILER_PROPERTY_PATTERN.matcher(key);
		if (namedMailerProperty.matches()) {
			final String propertyTail = namedMailerProperty.group("tail");
			final boolean isNestedNamedMailerDefinition = propertyTail.startsWith("mailers.");
			final boolean isNestedLockNamespace = propertyTail.startsWith("locked.");
			return !isNestedNamedMailerDefinition && !isNestedLockNamespace && isRecognizedKey("simplejavamail." + propertyTail);
		}
		return EXTRA_PROPERTY_PATTERN.matcher(key).matches() || CONNECTIONPOOL_CLUSTER_PROPERTY_PATTERN.matcher(key).matches();
	}
}
