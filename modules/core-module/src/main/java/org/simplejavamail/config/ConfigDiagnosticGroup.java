package org.simplejavamail.config;

import org.jetbrains.annotations.NotNull;

/**
 * Functional sections used to organize resolved Simple Java Mail configuration diagnostics.
 */
public enum ConfigDiagnosticGroup {
	SMTP_CONNECTION("SMTP connection"),
	TRANSPORT_SECURITY("Transport security"),
	PROXY("Proxy"),
	DIAGNOSTICS_AND_VALIDATION("Diagnostics and validation"),
	EXECUTION_AND_POOLING("Execution and pooling"),
	EMAIL_DEFAULTS("Email defaults"),
	DELIVERY_STATUS_NOTIFICATIONS("Delivery status notifications"),
	MESSAGE_SECURITY("Message security"),
	EMBEDDED_IMAGE_RESOLUTION("Embedded image resolution"),
	JAKARTA_MAIL_PROPERTIES("Jakarta Mail properties");

	private final String displayName;

	ConfigDiagnosticGroup(final String displayName) {
		this.displayName = displayName;
	}

	/**
	 * @return A developer-friendly heading for reports and user interfaces.
	 */
	@NotNull
	public String getDisplayName() {
		return displayName;
	}
}
