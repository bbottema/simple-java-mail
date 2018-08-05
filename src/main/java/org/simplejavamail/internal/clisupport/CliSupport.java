package org.simplejavamail.internal.clisupport;

import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.clisupport.annotation.CliSupported;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.MailerFromSessionBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.TreeSet;

import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.out;
import static org.simplejavamail.internal.clisupport.BuilderApiToCliCommandsMapper.generateCommandsAndSubcommands;
import static picocli.CommandLine.Model.CommandSpec;
import static picocli.CommandLine.Model.OptionSpec;

public class CliSupport {
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API = {EmailBuilder.EmailBuilderInstance.class, MailerBuilder.MailerRegularBuilder.class, MailerFromSessionBuilder.class};
	
	public static void runCLI(String[] args) {
		TreeSet<CliCommandData> parameterMap = generateCommandsAndSubcommands(RELEVANT_BUILDER_ROOT_API, new HashMap<Class<?>, Collection<CliCommandData>>());
		CommandLine commandLine = configurePicoCli(parameterMap);

		CommandLine.ParseResult pr = commandLine.parseArgs(args);
		CommandLine.printHelpIfRequested(pr.asCommandLineList(), out, err, Ansi.ON);
	}
	
	private static CommandLine configurePicoCli(TreeSet<CliCommandData> parameterMap) {
		CommandSpec rootCommandsHolder = createDefaultCommandSpec(true, "SimpleJavaMail", (String) null)
				.version("Simple Java Mail 6.0.0");
		
		rootCommandsHolder.usageMessage()
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
		
		CommandSpec sendRootCommand = createDefaultCommandSpec(false, "send", "Send an email, starting blank, replying to or forwarding another email");
		sendRootCommand.usageMessage().customSynopsis("\tsend [FLAGS] email:directives mailer:directives");
		CommandSpec connectRootCommand = createDefaultCommandSpec(false, "connect", "Test a server connection");
		connectRootCommand.usageMessage().customSynopsis("\tconnect [FLAGS] mailer:directives");
		CommandSpec validateRootCommand = createDefaultCommandSpec(false, "validate", "Validate an email");
		validateRootCommand.usageMessage().customSynopsis("\tvalidate [FLAGS] email:directives mailer:directives");
		
		populateRootCommands(sendRootCommand, parameterMap);
		populateRootCommands(connectRootCommand, parameterMap);
		populateRootCommands(validateRootCommand, parameterMap);
		
		rootCommandsHolder.addSubcommand(sendRootCommand.name(), sendRootCommand);
		rootCommandsHolder.addSubcommand(connectRootCommand.name(), connectRootCommand);
		rootCommandsHolder.addSubcommand(validateRootCommand.name(), validateRootCommand);
		
		return new CommandLine(rootCommandsHolder).setUsageHelpWidth(180);
	}
	
	private static void populateRootCommands(CommandSpec rootCommand, TreeSet<CliCommandData> parameterMap) {
		for (CliCommandData cliCommand : parameterMap) {
			if (cliCommand.applicableToRootCommand(CliSupported.RootCommand.valueOf(rootCommand.name()))) {
				CommandSpec builderApiCmd = createDefaultCommandSpec(false, cliCommand.getName(), cliCommand.getDescription().toArray(new String[]{}));
				
				for (CliParamData cliParamData : cliCommand.getPossibleParams()) {
					builderApiCmd.addOption(OptionSpec.builder(cliParamData.getName())
							.paramLabel(cliParamData.getHelpLabel())
							.type(cliParamData.getParamType())
							.description(cliParamData.formatDescription())
							.required(cliParamData.isRequired())
							.build());
				}
				
				rootCommand.addSubcommand(builderApiCmd.name(), builderApiCmd);
			}
		}
	}
	
	private static CommandSpec createDefaultCommandSpec(boolean isForRootCmd, @Nonnull String name, @Nullable String... descriptions) {
		final CommandSpec command = CommandSpec.create()
				.name(name)
				.mixinStandardHelpOptions(true);
		command.usageMessage()
				.description(descriptions)
				.headerHeading("%n@|bold,underline Usage|@:")
				.commandListHeading(format("%n@|bold,underline %s|@:%n", isForRootCmd ? "Commands" : "Directives"))
				.synopsisHeading(" ")
				.descriptionHeading("%n@|bold,underline Description|@:%n")
				.optionListHeading("%n@|bold,underline Flags/Parameters|@:%n")
				.parameterListHeading("%n@|bold,underline Parameters|@:%n")
				.footerHeading("%n")
				.footer("@|faint,italic http://www.simplejavamail.org/#/cli|@");
		return command;
	}
}
