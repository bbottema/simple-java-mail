package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.TestOnly;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.internal.clisupport.serialization.SerializationUtil;
import org.simplejavamail.internal.util.MiscUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.configurePicoCli;
import static org.simplejavamail.internal.clisupport.CliExecutionException.ERROR_INVOKING_BUILDER_API;

public class CliSupport {
	private static final Logger LOGGER = LoggerFactory.getLogger(CliSupport.class);

	private static final int CONSOLE_TEXT_WIDTH = 150;

	private static final File CLI_DATAFILE = new File(CliDataLocator.locateCLIDataFile());

	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API = new Class[] { EmailStartingBuilder.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class };
	private static final List<CliDeclaredOptionSpec> DECLARED_OPTIONS = produceCliDeclaredOptionSpec();
	private static final CommandLine PICOCLI_COMMAND_LINE = configurePicoCli(DECLARED_OPTIONS, CONSOLE_TEXT_WIDTH);

	private static List<CliDeclaredOptionSpec> produceCliDeclaredOptionSpec() {
		try {
			if (!CLI_DATAFILE.exists()) {
				LOGGER.info("Initial cli.data not found, writing to (one time action): {}", CLI_DATAFILE);
				List<CliDeclaredOptionSpec> declaredOptions = generateOptionsFromBuilderApi(RELEVANT_BUILDER_ROOT_API);
				MiscUtil.writeFileBytes(CLI_DATAFILE, SerializationUtil.serialize(declaredOptions));
			}
			return SerializationUtil.deserialize(MiscUtil.readFileBytes(CLI_DATAFILE));
		} catch (IOException e) {
			throw new CliExecutionException(ERROR_INVOKING_BUILDER_API, e);
		}
	}

	public static void runCLI(String[] args) {
		ParseResult pr = PICOCLI_COMMAND_LINE.parseArgs(cutOffAtHelp(args));

		if (!CliCommandLineConsumerUsageHelper.processAndApplyHelp(pr, CONSOLE_TEXT_WIDTH)) {
			CliReceivedCommand cliReceivedOptionData = CliCommandLineConsumer.consumeCommandLineInput(pr, DECLARED_OPTIONS);
			CliCommandLineConsumerResultHandler.processCliResult(cliReceivedOptionData);
		}
	}

	@TestOnly
	public static void listUsagesForAllOptions() {
		for (CliDeclaredOptionSpec declaredOption : DECLARED_OPTIONS) {
			runCLI(new String[] { "send", declaredOption.getName() + "--help" });
			System.out.print("\n\n\n");
		}
	}

	/**
	 * This is needed to avoid picocli to error out on other --params that are misconfigured.
	 */
	private static String[] cutOffAtHelp(String[] args) {
		List<String> argsToKeep = new ArrayList<>();
		for (String arg : args) {
			argsToKeep.add(arg);
			if (arg.endsWith(CliCommandLineProducer.OPTION_HELP_POSTFIX)) {
				break;
			}
		}

		if (argsToKeep.isEmpty()) {
			argsToKeep.add("--help");
		}

		return argsToKeep.toArray(new String[]{});
	}
}