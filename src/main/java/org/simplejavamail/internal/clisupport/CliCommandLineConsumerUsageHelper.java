package org.simplejavamail.internal.clisupport;

import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.Ansi.Style;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.out;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.EMPTY_PARAM_LABEL;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.OPTION_HELP_POSTFIX;

public class CliCommandLineConsumerUsageHelper {
	
	private static final ColorScheme COLOR_SCHEME = Help.defaultColorScheme(Ansi.ON).optionParams(Style.fg_yellow);
	
	@SuppressWarnings("SameParameterValue")
	static boolean processAndApplyHelp(CommandLine.ParseResult pr, int textWidth) {
        boolean helpApplied = CommandLine.printHelpIfRequested(pr.asCommandLineList(), out, err, COLOR_SCHEME);
    
        if (!helpApplied) {
            OptionSpec matchedOptionForHelp = checkHelpWantedForOptions(pr);
            if (matchedOptionForHelp != null) {
                CommandSpec command = convertOptionToCommandForUsageDisplay(matchedOptionForHelp);
                CommandLine.usage(new CommandLine(command).setUsageHelpWidth(textWidth), out, COLOR_SCHEME);
                helpApplied = true;
            }
        }
        return helpApplied;
    }
	
	@Nullable
    private static OptionSpec checkHelpWantedForOptions(CommandLine.ParseResult pr) {
        for (OptionSpec matchedOption : pr.matchedOptions()) {
            if (matchedOption.longestName().endsWith(OPTION_HELP_POSTFIX)) {
                return matchedOption;
            }
        }
        return pr.hasSubcommand() ? checkHelpWantedForOptions(pr.subcommand()) : null;
    }
    
    private static CommandSpec convertOptionToCommandForUsageDisplay(OptionSpec matchedOption) {
        CommandSpec command = CommandSpec.create();
        command.usageMessage()
                .customSynopsis(determineCustomSynopsis(matchedOption))
                .description(matchedOption.description())
                .headerHeading("%n@|bold,underline Usage|@:")
                .synopsisHeading(" ")
                .descriptionHeading("%n@|bold,underline Description|@:%n")
                .footerHeading("%n")
                .footer("@|faint,italic http://www.simplejavamail.org/#/cli|@");
        return command;
    }
    
    private static String determineCustomSynopsis(OptionSpec matchedOption) {
        final String NT = "@|cyan %s|@";
        final String NTP = "@|cyan %s|@ @|yellow %s|@";
    
        String paramName = determineParamName(matchedOption);
        return matchedOption.paramLabel().equals(EMPTY_PARAM_LABEL)
                ? format(NT, paramName)
                : format(NTP, paramName, matchedOption.paramLabel());
    }
    
    @Nonnull
    private static String determineParamName(OptionSpec matchedOption) {
        return matchedOption.longestName().substring(0, matchedOption.longestName().indexOf(CliCommandLineProducer.OPTION_HELP_POSTFIX));
    }
}