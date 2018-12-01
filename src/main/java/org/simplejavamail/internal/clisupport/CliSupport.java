package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.mailer.MailerFromSessionBuilder;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.util.ArrayList;
import java.util.List;

import static org.simplejavamail.email.EmailBuilder.EmailBuilderInstance;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.configurePicoCli;
import static org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder;

public class CliSupport implements CLIModule {
	
	private static final int CONSOLE_TEXT_WIDTH = 150;
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API 			= {EmailBuilderInstance.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class};
	public static final List<CliDeclaredOptionSpec> DECLARED_OPTIONS 	= generateOptionsFromBuilderApi(RELEVANT_BUILDER_ROOT_API);
	private static final CommandLine PICOCLI_COMMAND_LINE 				= configurePicoCli(DECLARED_OPTIONS, CONSOLE_TEXT_WIDTH);

	@Override
	public void runCLI(String[] args) {
		ParseResult pr = PICOCLI_COMMAND_LINE.parseArgs(cutOffAtHelp(args));
		
		if (!CliCommandLineConsumerUsageHelper.processAndApplyHelp(pr, CONSOLE_TEXT_WIDTH)) {
			CliReceivedCommand cliReceivedOptionData = CliCommandLineConsumer.consumeCommandLineInput(pr, DECLARED_OPTIONS);
			CliCommandLineConsumerResultHandler.processCliResult(cliReceivedOptionData);
		}
	}
	
	@Override
	public void listUsagesForAllOptions() {
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
		return argsToKeep.toArray(new String[]{});
	}
}