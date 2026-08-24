package org.simplejavamail.internal.clisupport.daemon;

/** Selects one-shot execution, opportunistic daemon acquisition, or strict daemon-only execution. */
enum DaemonExecutionMode {
	OFF,
	ACQUIRE,
	REQUIRE;

	static DaemonExecutionMode parse(final String value) {
		return switch (value.toLowerCase()) {
			case "off" -> OFF;
			case "acquire" -> ACQUIRE;
			case "require" -> REQUIRE;
			default -> throw new IllegalArgumentException("Expected --daemon=off|acquire|require, got: " + value);
		};
	}
}
