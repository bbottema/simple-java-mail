package org.simplejavamail.internal.clisupport;

import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.internal.clisupport.daemon.ArgumentFileExpander;
import org.simplejavamail.internal.clisupport.serialization.CliMetadataCache;
import org.simplejavamail.internal.clisupport.serialization.SerializationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.configurePicoCli;
import static org.simplejavamail.internal.clisupport.CliExecutionException.ERROR_INVOKING_BUILDER_API;

/**
 * Parses and executes the full generated CLI model for one request.
 * Every invocation owns fresh Picocli state, bounded output, and an explicit request context; the supplied execution
 * environment determines whether the resulting Mailer is closed immediately or leased from the daemon registry.
 * Daemon routing consults the generated model only for ambiguous help/selector candidates or actual command execution.
 */
public final class CliSupport {
	private static final Logger LOGGER = LoggerFactory.getLogger(CliSupport.class);
	private static final int CONSOLE_TEXT_WIDTH = 150;
	private static final int MAX_CAPTURED_OUTPUT = 256 * 1024;
	private static final String REGENERATE_METADATA_PROPERTY = "simplejavamail.cli.metadata.regenerate";
	private CliSupport() {
	}

	public static void runCLI(final String[] args) {
		runCLIWithExitCode(args);
	}

	public static int runCLIWithExitCode(final String[] args) {
		final Path workingDirectory = Path.of("").toAbsolutePath();
		try {
			return runExpandedCLIWithExitCode(ArgumentFileExpander.expand(args, workingDirectory), workingDirectory);
		} catch (RuntimeException e) {
			final CliExecutionResult result = failure(CliExitCode.CLI_ERROR, e,
					new BoundedOutput(MAX_CAPTURED_OUTPUT), new BoundedOutput(MAX_CAPTURED_OUTPUT));
			System.err.print(result.stderr());
			return result.exitCode();
		}
	}

	/** Runs one-shot arguments already expanded by the caller boundary, without interpreting at signs again. */
	public static int runExpandedCLIWithExitCode(final String[] args, final Path workingDirectory) {
		try (CliExecutionEnvironment environment = CliExecutionEnvironment.oneShot()) {
			final CliExecutionResult result = executeExpandedArguments(args, workingDirectory, UUID.randomUUID(), environment, null);
			System.out.print(result.stdout());
			System.err.print(result.stderr());
			return result.exitCode();
		} catch (RuntimeException e) {
			System.err.println("Unable to initialize the CLI configuration.");
			return CliExitCode.COMMAND_FAILED.code();
		}
	}

	/** Expands raw in-process arguments using the supplied request directory before parsing or executing builders. */
	public static CliExecutionResult execute(final String[] args, final Path workingDirectory, final UUID requestId,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		try {
			return executeExpandedArguments(ArgumentFileExpander.expand(args, workingDirectory), workingDirectory,
					requestId, environment, profileKey);
		} catch (RuntimeException e) {
			return failure(CliExitCode.CLI_ERROR, e, new BoundedOutput(MAX_CAPTURED_OUTPUT), new BoundedOutput(MAX_CAPTURED_OUTPUT));
		}
	}

	/** Executes the completed argument list from one-shot routing or an authenticated daemon request. */
	@SuppressWarnings("try")
	public static CliExecutionResult executeExpandedArguments(final String[] args, final Path workingDirectory, final UUID requestId,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		final BoundedOutput stdout = new BoundedOutput(MAX_CAPTURED_OUTPUT);
		final BoundedOutput stderr = new BoundedOutput(MAX_CAPTURED_OUTPUT);
		boolean commandExecutionStarted = false;
		try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
			 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);
			 CliRequestContext.Scope ignored = CliRequestContext.install(new CliRequestContext(requestId, workingDirectory))) {
			final List<CliDeclaredOptionSpec> declaredOptions = MetadataHolder.DECLARED_OPTIONS;
			final CommandLine commandLine = configurePicoCli(declaredOptions, CONSOLE_TEXT_WIDTH);
			final String[] selectedArguments = argumentsThroughFirstHelpOption(args, commandLine);
			final CommandLine.ParseResult parseResult = parseCommandLineInput(commandLine, selectedArguments);
			if (!CliCommandLineConsumerUsageHelper.writeHelpIfRequested(parseResult, CONSOLE_TEXT_WIDTH, out, err)) {
				final CliReceivedCommand received = CliCommandLineConsumer.consumeCommandLineInput(parseResult, declaredOptions);
				commandExecutionStarted = true;
				final CliExitCode category = CliCommandLineConsumerResultHandler.executeReceivedCommand(received, environment, profileKey, out);
				return new CliExecutionResult(category, stdout.asString(), stderr.asString());
			}
			return CliExecutionResult.success(stdout.asString(), stderr.asString());
		} catch (CommandLine.ParameterException e) {
			return failure(CliExitCode.CLI_ERROR, e, stdout, stderr);
		} catch (RuntimeException e) {
			return failure(commandExecutionStarted ? CliExitCode.COMMAND_FAILED : CliExitCode.CLI_ERROR,
					e, stdout, stderr);
		}
	}

	private static CliExecutionResult failure(final CliExitCode category, final RuntimeException exception,
			final BoundedOutput stdout, final BoundedOutput stderr) {
		final String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
		stderr.appendLine(message);
		return new CliExecutionResult(category, stdout.asString(), stderr.asString());
	}

	public static void listUsagesForAllOptions() {
		for (CliDeclaredOptionSpec declaredOption : MetadataHolder.DECLARED_OPTIONS) {
			runCLI(new String[] { "send", declaredOption.getName() + "--help" });
			System.out.print("\n\n\n");
		}
		runCLI(new String[] { "probe", CliCommandLineProducer.AUTHENTICATE_OPTION + CliCommandLineProducer.OPTION_HELP_POSTFIX });
	}

	private static List<CliDeclaredOptionSpec> loadOrGenerateDeclaredOptions() {
		final byte[] sourceApiFingerprint = CliSourceApiFingerprint.calculate();
		try {
			final byte[] encodedCache = Boolean.getBoolean(REGENERATE_METADATA_PROPERTY)
					? null : CliDataLocator.readCLIDataFile();
			if (encodedCache != null) {
				try {
					final byte[] payload = CliMetadataCache.unwrap(CliMetadataCache.Kind.CLI_OPTIONS,
							sourceApiFingerprint, encodedCache);
					return SerializationUtil.deserialize(payload);
				} catch (RuntimeException e) {
					LOGGER.warn("Could not use cli.data; regenerating the internal cache: {}", e.getMessage());
				}
			}
			LOGGER.info("Generating the internal CLI metadata cache");
			final List<CliDeclaredOptionSpec> declaredOptions = generateOptionsFromBuilderApi(CliBuilderApi.roots());
			final byte[] payload = SerializationUtil.serialize(declaredOptions);
			if (!CliDataLocator.persistCLIDataFile(
					CliMetadataCache.wrap(CliMetadataCache.Kind.CLI_OPTIONS, sourceApiFingerprint, payload))) {
				LOGGER.debug("Generated CLI metadata cache is running from a read-only classpath");
			}
			return declaredOptions;
		} catch (IOException e) {
			throw new CliExecutionException(ERROR_INVOKING_BUILDER_API, e);
		}
	}

	private static CommandLine.ParseResult parseCommandLineInput(final CommandLine commandLine, final String[] args) {
		try {
			return commandLine.parseArgs(args);
		} catch (CommandLine.UnmatchedArgumentException e) {
			// Picocli has already distinguished consumed values from unmatched options.
			for (final String arg : e.getUnmatched()) {
				if (arg.equals("--mailer:async") || arg.startsWith("--mailer:async=") || arg.equals("--mailer:async--help")) {
					throw new IllegalArgumentException("--mailer:async was removed in 10.0.0. Remove this option: send and connect already wait for completion. "
							+ "The daemon still runs separate requests concurrently.", e);
				}
			}
			throw e;
		}
	}

	/** Resolves an option-shaped help candidate for daemon routing without executing builders or converting their values. */
	public static boolean containsHelpOption(final String[] args) {
		return firstHelpOptionIndex(args, configurePicoCli(MetadataHolder.DECLARED_OPTIONS, CONSOLE_TEXT_WIDTH)) >= 0;
	}

	/** Resolves one unregistered daemon-selector spelling as a builder value, without executing builders or converting their inputs. */
	public static boolean isDaemonSelectorValue(final String[] args, final int selectorIndex) {
		// Selector spellings are unknown to this model. An equally unknown unique token lets Picocli identify this occurrence,
		// including repeated identical text and the first of multiple required parameters, using its normal value-consumption rules.
		final String marker = "--daemon=literal-" + UUID.randomUUID();
		final String[] markedArguments = args.clone();
		markedArguments[selectorIndex] = marker;
		final CommandLine commandLine = configurePicoCli(MetadataHolder.DECLARED_OPTIONS, CONSOLE_TEXT_WIDTH);
		commandLine.setUnmatchedArgumentsAllowed(true);
		collectRoutingInspectionErrors(commandLine);
		for (CommandLine.ParseResult parsed = commandLine.parseArgs(markedArguments); parsed != null; parsed = parsed.subcommand()) {
			for (OptionSpec option : parsed.matchedOptions()) {
				if (option.stringValues().contains(marker)) {
					return true;
				}
			}
		}
		return false;
	}

	private static void collectRoutingInspectionErrors(final CommandLine commandLine) {
		// Routing inspects ownership only; command execution later reports the original input's parsing errors.
		commandLine.getCommandSpec().parser().collectErrors(true);
		commandLine.getSubcommands().values().forEach(CliSupport::collectRoutingInspectionErrors);
	}

	private static String[] argumentsThroughFirstHelpOption(final String[] args, final CommandLine commandLine) {
		if (args.length == 0) {
			return new String[]{"--help"};
		}
		final int helpIndex = firstHelpOptionIndex(args, commandLine);
		return helpIndex < 0 ? args : Arrays.copyOf(args, helpIndex + 1);
	}

	private static int firstHelpOptionIndex(final String[] args, final CommandLine commandLine) {
		CommandLine currentCommand = commandLine;
		for (int index = 0; index < args.length; index++) {
			final String arg = args[index];
			if ("--".equals(arg)) {
				return -1;
			}
			final OptionSpec option = currentCommand.getCommandSpec().optionsMap().get(arg);
			if (option != null && (option.help() || option.usageHelp())) {
				return index;
			}
			final CommandLine subcommand = currentCommand.getSubcommands().get(arg);
			if (subcommand != null) {
				currentCommand = subcommand;
			}
		}
		return -1;
	}

	/** Defers expensive metadata validation and generation until command execution actually needs Picocli. */
	private static final class MetadataHolder {
		private static final List<CliDeclaredOptionSpec> DECLARED_OPTIONS = loadOrGenerateDeclaredOptions();
	}

	/** Captures bounded UTF-8 command output while reserving enough space for an explicit truncation marker. */
	private static final class BoundedOutput extends ByteArrayOutputStream {
		private static final String TRUNCATION_MARKER = System.lineSeparator() + "[output truncated]" + System.lineSeparator();
		private final int limit;
		private final int contentLimit;
		private boolean truncated;

		private BoundedOutput(final int limit) {
			this.limit = limit;
			this.contentLimit = limit - TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length - 4;
		}

		@Override
		public synchronized void write(final int value) {
			if (count < contentLimit) {
				super.write(value);
			} else {
				truncated = true;
			}
		}

		@Override
		public synchronized void write(final byte[] value, final int offset, final int length) {
			final int accepted = Math.max(0, Math.min(length, contentLimit - count));
			if (accepted > 0) {
				super.write(value, offset, accepted);
			}
			truncated |= accepted < length;
		}

		private synchronized void appendLine(final String message) {
			final byte[] bytes = (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
			write(bytes, 0, bytes.length);
		}

		private synchronized String asString() {
			final String value = toString(StandardCharsets.UTF_8);
			final String result = truncated ? value + TRUNCATION_MARKER : value;
			if (result.getBytes(StandardCharsets.UTF_8).length > limit) {
				throw new IllegalStateException("Bounded CLI output exceeded its internal byte limit");
			}
			return result;
		}
	}
}
