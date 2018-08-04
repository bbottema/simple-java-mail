package org.simplejavamail.internal.clisupport;

import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.MailerFromSessionBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;

import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.out;
import static org.simplejavamail.internal.clisupport.BuilderApiToCliCommandsMapper.generateCommandsAndSubcommands;
import static picocli.CommandLine.Model.CommandSpec;
import static picocli.CommandLine.Model.OptionSpec;

public class CliSupport {
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API = {EmailBuilder.EmailBuilderInstance.class, MailerBuilder.MailerRegularBuilder.class, MailerFromSessionBuilder.class};
	
	public static void runCLI(String[] args) {
		Collection<CliCommandData> parameterMap = generateCommandsAndSubcommands(RELEVANT_BUILDER_ROOT_API, new HashMap<Class<?>, Collection<CliCommandData>>());
		CommandLine commandLine = configurePicoCli(parameterMap);
		
		CommandLine.ParseResult pr = commandLine.parseArgs(args);
		CommandLine.printHelpIfRequested(pr.asCommandLineList(), out, err, Ansi.ON);
	}
	
	private static CommandLine configurePicoCli(Collection<CliCommandData> parameterMap) {
		CommandSpec rootCommand = applyingCommandDefaults(CommandSpec.create(), true, "SimpleJavaMail", (String) null)
				.version("Simple Java Mail 6.0.0");
		
		rootCommand.usageMessage()
				.customSynopsis("%n" +
						"\tsend     [FLAGS] email:directives mailer:directives",
						"\tconnect  [FLAGS] mailer:directives",
						"\tvalidate [FLAGS] email:directives mailer:directives")
				.description("Simple Java Mail Command Line Interface.%n" +
						"%n" +
						"All CLI support is a direct translation of the Simple Java Mail builder API and translates back into builder calls. " +
						"As such, the @|bold order of directives matters as well as combinations|@! Furthermore, all documentation is taken from the " +
						"builder API Javadoc.%n" +
						"%n" +
						"Note: All the regular functionality regarding properties and config files work with the CLI so you can provides defaults " +
						"as long as they are visible (on class path).");
		
		CommandSpec sendCmd = applyingCommandDefaults(CommandSpec.create(), false, "send", "Send an email");
		sendCmd.usageMessage().customSynopsis("\tsend [FLAGS] email:directives mailer:directives");
		CommandSpec connectCmd = applyingCommandDefaults(CommandSpec.create(), false, "connect", "Test a server connection");
		connectCmd.usageMessage().customSynopsis("\tconnect [FLAGS] mailer:directives");
		CommandSpec validateCmd = applyingCommandDefaults(CommandSpec.create(), false, "validate", "Validate an email");
		validateCmd.usageMessage().customSynopsis("\tvalidate [FLAGS] email:directives mailer:directives");
		
		configureCommands(sendCmd, parameterMap);
		configureCommands(connectCmd, parameterMap);
		configureCommands(validateCmd, parameterMap);
		
		rootCommand.addSubcommand(sendCmd.name(), sendCmd);
		rootCommand.addSubcommand(connectCmd.name(), connectCmd);
		rootCommand.addSubcommand(validateCmd.name(), validateCmd);
		
		return new CommandLine(rootCommand).setUsageHelpWidth(180);
	}
	
	private static void configureCommands(CommandSpec rootCommand, Collection<CliCommandData> parameterMap) {
		for (CliCommandData cliCommand : parameterMap) {
			CommandSpec builderApiCmd = applyingCommandDefaults(CommandSpec.create(), false, cliCommand.getName(), cliCommand.getDescription().toArray(new String[]{}));
			
			for (CliParamData cliParamData : cliCommand.getPossibleParams()) {
				builderApiCmd.addOption(OptionSpec.builder(cliParamData.getName())
						.paramLabel(cliParamData.getHelpLabel())
						.type(cliParamData.getParamType())
						.description(determineDescription(cliParamData))
						.required(cliParamData.isRequired())
						.build());
			}
			
			rootCommand.addSubcommand(builderApiCmd.name(), builderApiCmd);
		}
	}
	
	private static CommandSpec applyingCommandDefaults(@Nonnull CommandSpec cmd, boolean isForRootCmd, @Nonnull String name, @Nullable String... descriptions) {
		cmd
				.name(name)
				.mixinStandardHelpOptions(true)
				.usageMessage()
				.description(descriptions)
				.headerHeading("%n@|bold,underline Usage|@:")
				.commandListHeading(format("%n@|bold,underline %s|@:%n", isForRootCmd ? "Commands" : "Directives"))
				.synopsisHeading(" ")
				.descriptionHeading("%n@|bold,underline Description|@:%n")
				.optionListHeading("%n@|bold,underline Flags/Parameters|@:%n")
				.parameterListHeading("%n@|bold,underline Parameters|@:%n")
				.footerHeading("%n")
				.footer("@|faint,italic http://www.simplejavamail.org/#/cli|@");
		return cmd;
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
