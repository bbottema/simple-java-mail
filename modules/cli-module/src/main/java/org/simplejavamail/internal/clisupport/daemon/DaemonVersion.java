package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.internal.clisupport.CliVersion;

/** Central compatibility identifiers written to discovery state and every authenticated protocol frame. */
final class DaemonVersion {
	static final short PROTOCOL_MAJOR = 1;
	static final short PROTOCOL_MINOR = 0;
	static final String PRODUCT_VERSION = CliVersion.value();
	static final int PRODUCT_MAJOR = CliVersion.major();

	private DaemonVersion() {
	}
}
