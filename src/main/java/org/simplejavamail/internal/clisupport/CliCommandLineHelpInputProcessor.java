package org.simplejavamail.internal.clisupport;

import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import javax.annotation.Nullable;

import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.out;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.EMPTY_PARAM_LABEL;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.OPTION_HELP_POSTFIX;

public class CliCommandLineHelpInputProcessor {
    
    static boolean helpProcessedAndApplied(CommandLine.ParseResult pr, int textWidth) {
        boolean helpApplied = CommandLine.printHelpIfRequested(pr.asCommandLineList(), out, err, Ansi.ON);
    
        if (!helpApplied) {
            OptionSpec matchedOptionForHelp = checkHelpWantedForOptions(pr);
            if (matchedOptionForHelp != null) {
                CommandSpec command = convertOptionToCommandForUsageDisplay(matchedOptionForHelp);
                CommandLine.usage(new CommandLine(command).setUsageHelpWidth(textWidth), out, Ansi.ON);
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
        
        return matchedOption.paramLabel().equals(EMPTY_PARAM_LABEL)
                ? format(NT, matchedOption.longestName())
                : format(NTP, matchedOption.longestName(), matchedOption.paramLabel());
    }
}