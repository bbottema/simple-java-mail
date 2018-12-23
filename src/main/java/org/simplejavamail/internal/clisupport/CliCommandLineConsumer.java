package org.simplejavamail.internal.clisupport;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueConversionHelper;
import org.simplejavamail.internal.clisupport.model.CliCommandType;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.internal.clisupport.model.CliReceivedOptionData;
import org.slf4j.Logger;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.ListUtil.getLast;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.slf4j.LoggerFactory.getLogger;

class CliCommandLineConsumer {
    
    private static final Logger LOGGER = getLogger(CliCommandLineConsumer.class);
    
    // we reach here when terminal input was value and no help was requested
    static CliReceivedCommand consumeCommandLineInput(ParseResult providedCommand, @SuppressWarnings("SameParameterValue") Iterable<CliDeclaredOptionSpec> declaredOptions) {
        assumeTrue(providedCommand.hasSubcommand(), "Command was empty, expected one of: " + Arrays.toString(CliCommandType.values()));
        
        final ParseResult mailCommand = providedCommand.subcommand();
        final CliCommandType matchedCommand = CliCommandType.valueOf(mailCommand.commandSpec().name());
        final Map<CliDeclaredOptionSpec, OptionSpec> matchedOptionsInOrderProvision = matchProvidedOptions(declaredOptions, matchedCommand, mailCommand.matchedOptions());
    
        logParsedInput(matchedCommand, matchedOptionsInOrderProvision);
		
        List<CliReceivedOptionData> receivedOptions = new ArrayList<>();
        for (Entry<CliDeclaredOptionSpec, OptionSpec> cliOption : matchedOptionsInOrderProvision.entrySet()) {
            Class<?>[] expectedTypes = cliOption.getKey().getSourceMethod().getParameterTypes();
			List<String> providedStringValues = cliOption.getValue().getValue();
            assumeTrue(providedStringValues.size() == expectedTypes.length,
                    format("provided %s arguments, but need %s", providedStringValues.size(), expectedTypes.length));
			receivedOptions.add(new CliReceivedOptionData(cliOption.getKey(), convertProvidedOptionValues(providedStringValues, expectedTypes)));
			LOGGER.debug("\tconverted option values: {}", getLast(receivedOptions).getProvidedOptionValues());
        }
        
        return new CliReceivedCommand(matchedCommand, receivedOptions);
    }

	private static Map<CliDeclaredOptionSpec, OptionSpec> matchProvidedOptions(Iterable<CliDeclaredOptionSpec> declaredOptions, CliCommandType providedCommand, List<OptionSpec> providedOptions) {
		Map<CliDeclaredOptionSpec, OptionSpec> matchedProvidedOptions = new LinkedHashMap<>();
		
		for (OptionSpec providedOption : providedOptions) {
			for (CliDeclaredOptionSpec declaredOption : declaredOptions) {
				if (declaredOption.getApplicableToCliCommandTypes().contains(providedCommand) &&
						providedOption.longestName().equals(declaredOption.getName())) {
					matchedProvidedOptions.put(declaredOption, providedOption);
				}
			}
		}
        return matchedProvidedOptions;
    }

	private static List<Object> convertProvidedOptionValues(List<String> providedStringValues, Class<?>[] expectedTypes) {
		List<Object> providedValuesConverted = new ArrayList<>();
		for (int i = 0; i < providedStringValues.size(); i++) {
			providedValuesConverted.add(parseStringInput(providedStringValues.get(i), expectedTypes[i]));
		}
		return providedValuesConverted;
	}
    
    private static Object parseStringInput(@Nonnull String stringValue, @Nonnull Class<?> targetType) {
		try {
			return ValueConversionHelper.convert(stringValue, targetType);
		} catch (IncompatibleTypeException e) {
			LOGGER.error("Was unable to parse input from command line. The following conversions were tried and failed:");
			for (IncompatibleTypeException cause : e.getCauses()) {
				LOGGER.error(cause.getMessage(), cause);
			}
			throw e;
		}
	}
    
    private static void logParsedInput(CliCommandType matchedCommand, Map<CliDeclaredOptionSpec, OptionSpec> matchedOptionsInOrderProvision) {
        LOGGER.debug("processing mail command: {}", matchedCommand);
        for (Entry<CliDeclaredOptionSpec, OptionSpec> cliOption : matchedOptionsInOrderProvision.entrySet()) {
            CliDeclaredOptionSpec declaredOption = cliOption.getKey();
            OptionSpec providedOption = cliOption.getValue();
            Collection<String> values = providedOption.getValue();
            LOGGER.debug("\tgot option: {}, with {} value(s): {}", declaredOption.getName(), values.size(), values);
        }
    }
}