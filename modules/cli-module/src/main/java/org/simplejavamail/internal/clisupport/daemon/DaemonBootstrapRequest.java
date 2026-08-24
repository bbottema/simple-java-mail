package org.simplejavamail.internal.clisupport.daemon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Immutable routing decision produced before generated CLI metadata is initialized.
 * Parsing expands argument files, removes only daemon selectors, validates the selected instance and protocol limits,
 * and separates local help/one-shot execution, daemon-routed commands, and lifecycle management.
 */
record DaemonBootstrapRequest(Route route, DaemonExecutionMode mode, String instance, String managementAction,
		String[] commandArguments, Path workingDirectory) {
	private static final Pattern INSTANCE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

	enum Route { LOCAL, DAEMON_COMMAND, MANAGEMENT }

	static DaemonBootstrapRequest parse(final String[] originalArgs, final Path workingDirectory) {
		final String[] args = ArgumentFileExpander.expand(originalArgs, workingDirectory);
		final RoutingArguments routingArguments = extractRoutingArguments(args);
		return isManagementCommand(routingArguments.command())
				? managementRequest(routingArguments, workingDirectory)
				: commandRequest(routingArguments, workingDirectory);
	}

	private static RoutingArguments extractRoutingArguments(final String[] args) {
		final List<String> command = new ArrayList<>();
		DaemonExecutionMode mode = configuredMode();
		String instance = configuredInstance();
		boolean selectorSeen = false;
		for (int i = 0; i < args.length; i++) {
			final String arg = args[i];
			if ("-d".equals(arg) || "--daemon".equals(arg)) {
				mode = mergeMode(mode, DaemonExecutionMode.ACQUIRE, selectorSeen);
				selectorSeen = true;
			} else if (arg.startsWith("--daemon=")) {
				mode = mergeMode(mode, DaemonExecutionMode.parse(arg.substring("--daemon=".length())), selectorSeen);
				selectorSeen = true;
			} else if ("--no-daemon".equals(arg)) {
				mode = mergeMode(mode, DaemonExecutionMode.OFF, selectorSeen);
				selectorSeen = true;
			} else if (arg.startsWith("--daemon-instance=")) {
				instance = validateInstance(arg.substring("--daemon-instance=".length()));
			} else if ("--daemon-instance".equals(arg)) {
				if (++i >= args.length) {
					throw new IllegalArgumentException("--daemon-instance requires a value");
				}
				instance = validateInstance(args[i]);
			} else {
				command.add(arg);
			}
		}
		return new RoutingArguments(mode, instance, command);
	}

	private static boolean isManagementCommand(final List<String> command) {
		return !command.isEmpty() && "daemon".equals(command.get(0));
	}

	private static DaemonBootstrapRequest managementRequest(final RoutingArguments routingArguments,
			final Path workingDirectory) {
		final List<String> command = routingArguments.command();
		if (command.size() > 2) {
			final List<String> actionArguments = command.subList(2, command.size());
			if (!actionArguments.stream().allMatch(DaemonBootstrapRequest::isHelpOrVersion)) {
				throw new IllegalArgumentException("Unexpected daemon command arguments");
			}
		}
		return new DaemonBootstrapRequest(Route.MANAGEMENT, routingArguments.mode(), routingArguments.instance(),
				determineManagementAction(command), new String[0], workingDirectory);
	}

	private static String determineManagementAction(final List<String> command) {
		if (command.stream().anyMatch(DaemonBootstrapRequest::isVersion)) {
			return "version";
		}
		if (command.size() > 2
				&& command.subList(2, command.size()).stream().anyMatch(DaemonBootstrapRequest::isHelpOrVersion)) {
			return "help";
		}
		return command.size() > 1 ? command.get(1).toLowerCase(Locale.ROOT) : "help";
	}

	private static DaemonBootstrapRequest commandRequest(final RoutingArguments routingArguments,
			final Path workingDirectory) {
		final List<String> command = routingArguments.command();
		final boolean helpOrVersion = command.isEmpty()
				|| command.stream().anyMatch(DaemonBootstrapRequest::isHelpOrVersion);
		final Route route = helpOrVersion || routingArguments.mode() == DaemonExecutionMode.OFF
				? Route.LOCAL : Route.DAEMON_COMMAND;
		if (route == Route.DAEMON_COMMAND) {
			DaemonProtocol.validateRequestPayload(workingDirectory.toAbsolutePath().normalize(), command);
		}
		return new DaemonBootstrapRequest(route, routingArguments.mode(), routingArguments.instance(), null,
				command.toArray(new String[0]), workingDirectory);
	}

	private static boolean isHelpOrVersion(final String arg) {
		return "--help".equals(arg) || "-h".equals(arg) || isVersion(arg) || arg.endsWith("--help");
	}

	private static boolean isVersion(final String arg) {
		return "--version".equals(arg) || "-V".equals(arg);
	}

	private static DaemonExecutionMode configuredMode() {
		final String property = System.getProperty("simplejavamail.cli.daemon");
		final String environment = System.getenv("SIMPLEJAVAMAIL_CLI_DAEMON");
		final String value = property != null ? property : environment;
		return value == null || value.isBlank() ? DaemonExecutionMode.OFF : DaemonExecutionMode.parse(value);
	}

	private static String configuredInstance() {
		final String property = System.getProperty("simplejavamail.cli.daemon.instance");
		final String environment = System.getenv("SIMPLEJAVAMAIL_CLI_DAEMON_INSTANCE");
		final String value = property != null ? property : environment;
		return validateInstance(value == null || value.isBlank() ? "default" : value);
	}

	private static DaemonExecutionMode mergeMode(final DaemonExecutionMode current, final DaemonExecutionMode next,
			final boolean selectorSeen) {
		if (selectorSeen && current != next) {
			throw new IllegalArgumentException("Conflicting daemon execution selectors");
		}
		return next;
	}

	static String validateInstance(final String instance) {
		if (!INSTANCE_NAME.matcher(instance).matches()) {
			throw new IllegalArgumentException("Daemon instance names must match " + INSTANCE_NAME.pattern());
		}
		return instance;
	}

	private record RoutingArguments(DaemonExecutionMode mode, String instance, List<String> command) {
	}
}
