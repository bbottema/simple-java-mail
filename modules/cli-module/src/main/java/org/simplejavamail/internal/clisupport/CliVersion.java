package org.simplejavamail.internal.clisupport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Resolves the CLI artifact version without initializing generated command metadata. */
public final class CliVersion {
	private static final String VALUE = resolveVersion();

	private CliVersion() {
	}

	public static String value() {
		return VALUE;
	}

	public static int major() {
		try {
			return Integer.parseInt(VALUE.split("[.-]", 2)[0]);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String resolveVersion() {
		final String override = System.getProperty("simplejavamail.cli.version");
		if (override != null && !override.isBlank()) {
			return override;
		}
		final String implementationVersion = CliVersion.class.getPackage().getImplementationVersion();
		if (implementationVersion != null && !implementationVersion.isBlank()) {
			return implementationVersion;
		}
		try (InputStream input = CliVersion.class.getResourceAsStream(
				"/META-INF/maven/org.simplejavamail/cli-module/pom.properties")) {
			if (input != null) {
				final Properties properties = new Properties();
				properties.load(input);
				return properties.getProperty("version", "development");
			}
		} catch (IOException ignored) {
			// A missing package descriptor is expected in some development launchers.
		}
		return "development";
	}
}
