package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.annotation.CliCommand;
import org.simplejavamail.internal.clisupport.model.CliOptionData;
import org.simplejavamail.internal.util.Preconditions;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import static java.lang.System.out;

class CliCommandLineConsumer {
    // we reach here when terminal input was value and no help was requested
    static void consumeCommandLineInput(ParseResult providedCommand, Set<CliOptionData> declaredOptions) {
        Preconditions.assumeTrue(providedCommand.hasSubcommand(), "Command was empty, expected one of: " + Arrays.toString(CliCommand.values()));
        
        final ParseResult mailCommand = providedCommand.subcommand();
        final CliCommand matchedCommand = CliCommand.valueOf(mailCommand.commandSpec().name());
        final TreeMap<CliOptionData, OptionSpec> matchedOptionsInOrderProvision = matchProvidedOptions(declaredOptions, matchedCommand, mailCommand.matchedOptions());
    
        out.println("mail command: " + matchedCommand);
        for (Entry<CliOptionData, OptionSpec> cliOption : matchedOptionsInOrderProvision.entrySet()) {
            out.printf("\toption: %s, value(s): %s%n", cliOption.getKey().getName(), cliOption.getValue().getValue());
        }
    }
    
    private static TreeMap<CliOptionData, OptionSpec> matchProvidedOptions(Set<CliOptionData> declaredOptions, CliCommand providedCommand, List<OptionSpec> providedOptions) {
        TreeMap<CliOptionData, OptionSpec> matchedProvidedOptions = new TreeMap<>();
        
        for (CliOptionData declaredOption : declaredOptions) {
            if (declaredOption.getApplicableToCliCommands().contains(providedCommand)) {
                for (OptionSpec providedOption : providedOptions) {
                    if (providedOption.longestName().equals(declaredOption.getName())) {
                        matchedProvidedOptions.put(declaredOption, providedOption);
                    }
                }
            }
        }
        return matchedProvidedOptions;
    }
}
