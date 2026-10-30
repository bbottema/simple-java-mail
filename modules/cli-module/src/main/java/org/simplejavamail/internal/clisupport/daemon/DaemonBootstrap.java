package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliExitCode;
import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.CliVersion;

import java.nio.file.Path;

/**
 * Routes local, daemon-backed, and lifecycle commands before execution. Ordinary routed commands, discovery and
 * management stay independent of generated metadata; option-shaped help candidates consult the model to keep values
 * literal. The ordinary one-shot executor remains the default command path.
 */
public final class DaemonBootstrap {
	private DaemonBootstrap() {
	}

	public static int run(final String[] arguments) {
		return route(arguments, false);
	}

	/** Runs a dedicated CLI process; an authenticated daemon stop may ultimately terminate that process. */
	public static int runProcess(final String[] arguments) {
		return route(arguments, true);
	}

	private static int route(final String[] arguments, final boolean standaloneProcess) {
		final DaemonBootstrapRequest request;
		try {
			request = DaemonBootstrapRequest.parse(arguments, Path.of("").toAbsolutePath());
		} catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			return CliExitCode.CLI_ERROR.code();
		}

		return switch (request.route()) {
			case LOCAL -> CliSupport.runExpandedCLIWithExitCode(request.commandArguments(), request.workingDirectory());
			case DAEMON_COMMAND -> writeResultAndReturnExitCode(executeDaemonCommand(request));
			case MANAGEMENT -> writeResultAndReturnExitCode(executeManagementCommand(request, standaloneProcess));
		};
	}

	private static CliExecutionResult executeDaemonCommand(final DaemonBootstrapRequest request) {
		final DaemonClient client = new DaemonClient(request.instance());
		if (request.mode() == DaemonExecutionMode.ACQUIRE) {
			final CliExecutionResult start = DaemonProcessLauncher.start(request.instance());
			if (start.category() != CliExitCode.SUCCESS) {
				return start;
			}
		}
		return client.execute(request.workingDirectory(), request.commandArguments());
	}

	private static CliExecutionResult executeManagementCommand(final DaemonBootstrapRequest request, final boolean standaloneProcess) {
		return switch (request.managementAction()) {
			case "help", "--help", "-h" -> new CliExecutionResult(CliExitCode.SUCCESS, managementHelp(), "");
			case "version" -> new CliExecutionResult(CliExitCode.SUCCESS,
					"Simple Java Mail " + CliVersion.value() + System.lineSeparator(), "");
			case "run" -> resultForExitCode(new DaemonServer(request.instance(), null,
					standaloneProcess ? DaemonShutdown.standalone() : DaemonShutdown.embedded()).run());
			case "start" -> DaemonProcessLauncher.start(request.instance());
			case "status" -> new DaemonClient(request.instance()).status();
			case "stop" -> new DaemonClient(request.instance()).stop();
			case "restart" -> restart(request.instance());
			default -> new CliExecutionResult(CliExitCode.CLI_ERROR, "",
					"Unknown daemon command. Expected run, start, stop, status, or restart." + System.lineSeparator());
		};
	}

	private static CliExecutionResult restart(final String instance) {
		final CliExecutionResult stopped = new DaemonClient(instance).stop();
		if (stopped.category() != CliExitCode.SUCCESS && stopped.category() != CliExitCode.DAEMON_ABSENT) {
			return stopped;
		}
		return DaemonProcessLauncher.start(instance);
	}

	private static CliExecutionResult resultForExitCode(final int code) {
		return new CliExecutionResult(CliExitCode.fromCode(code), "", "");
	}

	private static int writeResultAndReturnExitCode(final CliExecutionResult result) {
		System.out.print(result.stdout());
		System.err.print(result.stderr());
		return result.exitCode();
	}

	private static String managementHelp() {
		return "Simple Java Mail daemon commands:" + System.lineSeparator()
				+ "  sjm daemon run      Run the selected daemon in the foreground" + System.lineSeparator()
				+ "  sjm daemon start    Start the selected per-user daemon" + System.lineSeparator()
				+ "  sjm daemon stop     Stop it through the authenticated local protocol" + System.lineSeparator()
				+ "  sjm daemon status   Print scriptable status" + System.lineSeparator()
				+ "  sjm daemon restart  Stop, then start the selected daemon" + System.lineSeparator()
				+ "  --daemon-instance=<name> selects an isolated instance (default: default)" + System.lineSeparator();
	}
}
