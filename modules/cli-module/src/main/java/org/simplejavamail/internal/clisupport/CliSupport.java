package org.simplejavamail.internal.clisupport;

import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.internal.clisupport.serialization.CliMetadataCache;
import org.simplejavamail.internal.clisupport.serialization.SerializationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.configurePicoCli;
import static org.simplejavamail.internal.clisupport.CliExecutionException.ERROR_INVOKING_BUILDER_API;

/**
 * Parses and executes the full generated CLI model for one request.
 * Every invocation owns fresh Picocli state, bounded output, and an explicit request context; the supplied execution
 * environment determines whether the resulting Mailer is closed immediately or leased from the daemon registry.
 * Daemon bootstrap code references this class only after routing selects actual command execution.
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
		try (CliExecutionEnvironment environment = CliExecutionEnvironment.oneShot()) {
			final CliExecutionResult result = execute(args, Path.of("").toAbsolutePath(), UUID.randomUUID(), environment, null);
			System.out.print(result.stdout());
			System.err.print(result.stderr());
			return result.exitCode();
		} catch (RuntimeException e) {
			System.err.println("Unable to initialize the CLI configuration.");
			return CliExitCode.COMMAND_FAILED.code();
		}
	}

	@SuppressWarnings("try")
	public static CliExecutionResult execute(final String[] args, final Path workingDirectory, final UUID requestId,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		final BoundedOutput stdout = new BoundedOutput(MAX_CAPTURED_OUTPUT);
		final BoundedOutput stderr = new BoundedOutput(MAX_CAPTURED_OUTPUT);
		boolean commandExecutionStarted = false;
		try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
			 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);
			 CliRequestContext.Scope ignored = CliRequestContext.install(new CliRequestContext(requestId, workingDirectory))) {
			final List<CliDeclaredOptionSpec> declaredOptions = MetadataHolder.DECLARED_OPTIONS;
			final CommandLine commandLine = configurePicoCli(declaredOptions, CONSOLE_TEXT_WIDTH);
			final CommandLine.ParseResult parseResult = commandLine.parseArgs(argumentsThroughFirstHelpOption(args));
			if (!CliCommandLineConsumerUsageHelper.writeHelpIfRequested(parseResult, CONSOLE_TEXT_WIDTH, out, err)) {
				final CliReceivedCommand received = CliCommandLineConsumer.consumeCommandLineInput(parseResult, declaredOptions);
				commandExecutionStarted = true;
				CliCommandLineConsumerResultHandler.executeReceivedCommand(received, environment, profileKey);
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

	private static String[] argumentsThroughFirstHelpOption(final String[] args) {
		final List<String> argsToKeep = new ArrayList<>();
		for (final String arg : args) {
			argsToKeep.add(arg);
			if (arg.endsWith(CliCommandLineProducer.OPTION_HELP_POSTFIX)) {
				break;
			}
		}
		if (argsToKeep.isEmpty()) {
			argsToKeep.add("--help");
		}
		return argsToKeep.toArray(new String[0]);
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
