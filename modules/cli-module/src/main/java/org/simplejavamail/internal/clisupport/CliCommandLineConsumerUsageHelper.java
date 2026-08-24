package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.Ansi.Style;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.PrintStream;

import static java.lang.String.format;
import static org.simplejavamail.internal.clisupport.CliColorScheme.OPTION_STYLE;
import static org.simplejavamail.internal.clisupport.CliColorScheme.OPTION_VALUE_STYLE;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.EMPTY_PARAM_LABEL;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.OPTION_HELP_POSTFIX;

/**
 * Writes standard or option-specific help to the current request's streams before command execution starts.
 * Its result tells the caller whether help consumed the parsed command.
 */
final class CliCommandLineConsumerUsageHelper {

	private static final ColorScheme COLOR_SCHEME = Help.defaultColorScheme(Ansi.AUTO).optionParams(Style.fg_yellow);

	private CliCommandLineConsumerUsageHelper() {
	}

	@SuppressWarnings("SameParameterValue")
	static boolean writeHelpIfRequested(final CommandLine.ParseResult parseResult, final int textWidth,
			final PrintStream out, final PrintStream err) {
		if (CommandLine.printHelpIfRequested(parseResult.asCommandLineList(), out, err, COLOR_SCHEME)) {
			return true;
		}
		final OptionSpec optionHelp = findOptionHelpRequest(parseResult);
		if (optionHelp == null) {
			return false;
		}
		writeOptionHelp(optionHelp, textWidth, out);
		return true;
	}

	private static void writeOptionHelp(final OptionSpec optionHelp, final int textWidth, final PrintStream out) {
		final CommandLine optionHelpCommand = new CommandLine(optionHelpCommand(optionHelp)).setUsageHelpWidth(textWidth);
		CommandLine.usage(optionHelpCommand, out, COLOR_SCHEME);
	}

	@Nullable
	private static OptionSpec findOptionHelpRequest(final CommandLine.ParseResult parseResult) {
		for (final OptionSpec matchedOption : parseResult.matchedOptions()) {
			if (matchedOption.longestName().endsWith(OPTION_HELP_POSTFIX)) {
				return matchedOption;
			}
		}
		return parseResult.hasSubcommand() ? findOptionHelpRequest(parseResult.subcommand()) : null;
	}

	private static CommandSpec optionHelpCommand(final OptionSpec matchedOption) {
		final CommandSpec command = CommandSpec.create();
		command.usageMessage()
				.customSynopsis(optionSynopsis(matchedOption))
				.description(matchedOption.description())
                .headerHeading("%n@|bold,underline Usage|@:")
                .synopsisHeading(" ")
                .descriptionHeading("%n@|bold,underline Description|@:%n")
                .footerHeading("%n")
                .footer("@|faint,italic https://www.simplejavamail.org/cli.html|@");
		return command;
	}

	private static String optionSynopsis(final OptionSpec matchedOption) {
		final String styledOptionNoParameters = "@|%s %s|@";
		final String stylesOptionWithParameters = "@|%s %s|@ @|%s %s|@";

		final String optionName = optionName(matchedOption);
		return matchedOption.paramLabel().equals(EMPTY_PARAM_LABEL)
				? format(styledOptionNoParameters, OPTION_STYLE, optionName)
				: format(stylesOptionWithParameters, OPTION_STYLE, optionName, OPTION_VALUE_STYLE,
						matchedOption.paramLabel());
	}

	@NotNull
	private static String optionName(final OptionSpec matchedOption) {
		final String helpOptionName = matchedOption.longestName();
		return helpOptionName.substring(0, helpOptionName.indexOf(OPTION_HELP_POSTFIX));
	}
}
