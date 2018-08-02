package org.simplejavamail.internal.clisupport;

import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.MailerFromSessionBuilder;
import picocli.CommandLine;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;

import static org.simplejavamail.internal.clisupport.BuilderToCliCommandsMapper.generateCommandsAndSubcommands;
import static picocli.CommandLine.Model.CommandSpec;
import static picocli.CommandLine.Model.OptionSpec;

public class CliSupport {
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API = {EmailBuilder.EmailBuilderInstance.class, MailerBuilder.MailerRegularBuilder.class, MailerFromSessionBuilder.class};
	
	public static void runCLI(String[] args) {
		Collection<CliCommandData> parameterMap = generateCommandsAndSubcommands(RELEVANT_BUILDER_ROOT_API, new HashMap<Class<?>, Collection<CliCommandData>>());
		CommandLine commandLine = configurePicoCli(parameterMap);
		
		CommandLine.ParseResult pr = commandLine.parseArgs(args);
		CommandLine.printHelpIfRequested(pr);
	}
	
	private static CommandLine configurePicoCli(Collection<CliCommandData> parameterMap) {
		CommandSpec mainCommand = CommandSpec.create()
				.name("send")
				.version("6.0.0")
				.mixinStandardHelpOptions(true);
		configureCommands(mainCommand, parameterMap);
		return new CommandLine(mainCommand);
	}
	
	private static void configureCommands(CommandSpec spec, Collection<CliCommandData> parameterMap) {
		for (CliCommandData cliCommand : parameterMap) {
			CommandSpec cmd = CommandSpec.create().mixinStandardHelpOptions(true);
			
			for (CliParamData cliParamData : cliCommand.getPossibleParams()) {
				cmd.addOption(OptionSpec.builder(cliParamData.getName())
						.paramLabel(cliParamData.getHelpLabel())
						.type(cliParamData.getParamType())
						.description(determineDescription(cliParamData))
						.build());
			}
			
			spec.addSubcommand(cliCommand.getName(), cmd);
		}
	}
	
	private static String determineDescription(CliParamData cliParamData) {
		if (cliParamData.getExamples().length > 0) {
			if (cliParamData.getExamples().length == 1) {
				return cliParamData.getDescription() + "\nexample: " + cliParamData.getExamples()[0];
			} else {
				return cliParamData.getDescription() + "\nexamples: " + formatExamples(cliParamData.getExamples());
			}
		}
		return cliParamData.getDescription();
	}
	
	private static String formatExamples(String[] examples) {
		String examplesArray = Arrays.toString(examples);
		return examplesArray.substring(1, examplesArray.length() - 1);
	}
}
