package org.simplejavamail.internal.clisupport;

import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.clisupport.annotation.CliSupported;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.MailerFromSessionBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

import static java.lang.System.err;
import static java.lang.System.out;
import static org.simplejavamail.internal.clisupport.BuilderApiToCliCommandsMapper.generateCommandsAndSubcommands;
import static picocli.CommandLine.Model.CommandSpec;
import static picocli.CommandLine.Model.OptionSpec;

public class CliSupport {
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API = {EmailBuilder.EmailBuilderInstance.class, MailerBuilder.MailerRegularBuilder.class, MailerFromSessionBuilder.class};
	
	public static void runCLI(String[] args) {
		TreeSet<CliCommandData> parameterMap = generateCommandsAndSubcommands(RELEVANT_BUILDER_ROOT_API, new HashMap<Class<?>, Collection<CliCommandData>>());
		CommandLine.ParseResult pr = chooseCommandLine(args, parameterMap).parseArgs(args);

		if (pr.isUsageHelpRequested()) {
			CommandLine.printHelpIfRequested(pr.asCommandLineList(), out, err, Ansi.ON);
		} else {
			for (OptionSpec matchedOption : pr.matchedOptions()) {
				if (matchedOption.usageHelp()) {
					CommandLine.usage(convertOptionToCommandUsage(matchedOption));
					break;
				}
			}
		}
	}

	/**
	 * @deprecated this is probably not needed with the extra help option added for each name
	 */
	@Deprecated
	private static CommandLine chooseCommandLine(String[] args, TreeSet<CliCommandData> parameterMap) {
		for (String arg: args) {
			if (!arg.equals("--help") && arg.contains("--help")) {
				return configurePicoCli(parameterMap, true);
			}
		}
		return configurePicoCli(parameterMap, false);
	}

	private static CommandLine configurePicoCli(TreeSet<CliCommandData> parameterMap, boolean fullDescription) {
		CommandSpec rootCommandsHolder = createDefaultCommandSpec("SimpleJavaMail", (String) null)
				.version("Simple Java Mail 6.0.0");
		
		rootCommandsHolder.usageMessage()
				.customSynopsis("%n" +
						"\tsend     [options] email:options mailer:options",
						"\tconnect  [options] mailer:options",
						"\tvalidate [options] email:options mailer:options",
						"\tconvert  [options] email:options")
				.description("Simple Java Mail Command Line Interface.%n" +
						"%n" +
						"All CLI support is a direct translation of the Simple Java Mail builder API and translates back into builder calls. " +
						"As such, the @|bold order of directives matters as well as combinations|@! Furthermore, all documentation is taken from the " +
						"builder API Javadoc.%n" +
						"%n" +
						"Note: All the regular functionality regarding properties and config files work with the CLI so you can provides defaults " +
						"as long as they are visible (on class path).");

		createRootCommand(rootCommandsHolder, "send", "Send an email, starting blank, replying to or forwarding another email", "\tsend [options] email:options mailer:options", parameterMap, fullDescription);
		createRootCommand(rootCommandsHolder, "connect", "Test a server connection", "\tconnect [options] mailer:options", parameterMap, fullDescription);
		createRootCommand(rootCommandsHolder, "validate", "Validate an email", "\tvalidate [options] email:options mailer:options", parameterMap, fullDescription);
		createRootCommand(rootCommandsHolder, "convert", "Convert between email types", "\tvalidate [options] email:options", parameterMap, fullDescription);

		return new CommandLine(rootCommandsHolder).setUsageHelpWidth(180).setSeparator(" ");
	}

	private static void createRootCommand(CommandSpec rootCommandsHolder, String name, String description, String synopsis,
			TreeSet<CliCommandData> parameterMap, boolean fullDescription) {
		CommandSpec rootCommand = createDefaultCommandSpec(name, description);
		rootCommand.usageMessage().customSynopsis(synopsis);
		populateRootCommands(rootCommand, parameterMap, fullDescription);
		rootCommandsHolder.addSubcommand(rootCommand.name(), rootCommand);
	}

	private static void populateRootCommands(CommandSpec rootCommand, TreeSet<CliCommandData> parameterMap, boolean fullDescription) {
		for (CliCommandData cliCommand : parameterMap) {
			if (cliCommand.applicableToRootCommand(CliSupported.RootCommand.valueOf(rootCommand.name()))) {
				rootCommand.addOption(OptionSpec.builder(cliCommand.getName())
						.type(String.class)
						.paramLabel(determineParamLabel(cliCommand.getPossibleParams()))
						.description(determineDescription(cliCommand, false))
						//.required(/*FIXME cliCommand.isRequired()*/)
						.build());
				rootCommand.addOption(OptionSpec.builder(cliCommand.getName() + "--help")
						.hidden(true)
						.help(true)
						.description(determineDescription(cliCommand, true))
						//.required(/*FIXME cliCommand.isRequired()*/)
						.build());
			}
		}
	}

	private static String[] determineDescription(CliCommandData cliCommand, boolean fullDescription) {
		List<String> descriptions = new ArrayList<>(cliCommand.getDescription());
		for (CliParamData possibleParam : cliCommand.getPossibleParams()) {
			descriptions.add(possibleParam.getName() + ": " + possibleParam.formatDescription());
		}
		if (!fullDescription && descriptions.size() > 1) {
			return new String[] {descriptions.get(0) + " (...more)"};
		} else {
			return descriptions.toArray(new String[]{});
		}
	}

	private static String determineParamLabel(List<CliParamData> possibleParams) {
		final StringBuilder paramLabel = new StringBuilder();
		for (CliParamData possibleParam : possibleParams) {
			paramLabel.append(possibleParam.getName()).append("=").append(possibleParam.getHelpLabel()).append(" ");
		}
		return paramLabel.toString().trim();
	}

	private static CommandSpec createDefaultCommandSpec(@Nonnull String name, @Nullable String... descriptions) {
		final CommandSpec command = CommandSpec.create()
				.name(name)
				.mixinStandardHelpOptions(true);
		command.usageMessage()
				.description(descriptions)
				.headerHeading("%n@|bold,underline Usage|@:")
				.commandListHeading("%n@|bold,underline Commands|@:%n")
				.synopsisHeading(" ")
				.descriptionHeading("%n@|bold,underline Description|@:%n")
				.optionListHeading("%n@|bold,underline Options|@:%n")
				.parameterListHeading("%n@|bold,underline Parameters|@:%n")
				.footerHeading("%n")
				.footer("@|faint,italic http://www.simplejavamail.org/#/cli|@");
		return command;
	}
}
