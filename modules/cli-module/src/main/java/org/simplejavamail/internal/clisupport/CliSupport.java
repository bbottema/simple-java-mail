package org.simplejavamail.internal.clisupport;

import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.util.ArrayList;
import java.util.List;

import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.configurePicoCli;

public class CliSupport {
	
	private static final int CONSOLE_TEXT_WIDTH = 150;
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API 			= {EmailStartingBuilder.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class};
	private static final List<CliDeclaredOptionSpec> DECLARED_OPTIONS 	= generateOptionsFromBuilderApi(RELEVANT_BUILDER_ROOT_API);
	private static final CommandLine PICOCLI_COMMAND_LINE 				= configurePicoCli(DECLARED_OPTIONS, CONSOLE_TEXT_WIDTH);

	public static void runCLI(String[] args) {
		ParseResult pr = PICOCLI_COMMAND_LINE.parseArgs(cutOffAtHelp(args));
		
		if (!CliCommandLineConsumerUsageHelper.processAndApplyHelp(pr, CONSOLE_TEXT_WIDTH)) {
			CliReceivedCommand cliReceivedOptionData = CliCommandLineConsumer.consumeCommandLineInput(pr, DECLARED_OPTIONS);
			CliCommandLineConsumerResultHandler.processCliResult(cliReceivedOptionData);
		}
	}
	
	public static void listUsagesForAllOptions() {
		for (CliDeclaredOptionSpec declaredOption : DECLARED_OPTIONS) {
			runCLI(new String[]{"send", declaredOption.getName() + "--help"});
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