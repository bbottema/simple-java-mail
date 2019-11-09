package org.simplejavamail.internal.clisupport;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueConversionHelper;
import org.simplejavamail.api.internal.clisupport.model.CliCommandType;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.internal.util.MiscUtil;
import org.slf4j.Logger;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.bbottema.javareflection.TypeUtils.containsAnnotation;
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
        final Map<CliDeclaredOptionSpec, OptionSpec> matchedOptionsInOrderProvision = matchProvidedOptions(declaredOptions, mailCommand.matchedOptions());
    
        logParsedInput(matchedCommand, matchedOptionsInOrderProvision);
		
        List<CliReceivedOptionData> receivedOptions = new ArrayList<>();
        for (Entry<CliDeclaredOptionSpec, OptionSpec> cliOption : matchedOptionsInOrderProvision.entrySet()) {
			final Method sourceMethod = cliOption.getKey().getSourceMethod();
			final int mandatoryParameters = MiscUtil.countMandatoryParameters(sourceMethod);
			final List<String> providedStringValues = cliOption.getValue().getValue();
            assumeTrue(providedStringValues.size() >= mandatoryParameters,
                    format("provided %s arguments, but need at least %s", providedStringValues.size(), mandatoryParameters));
            assumeTrue(providedStringValues.size() <= sourceMethod.getParameterTypes().length,
                    format("provided %s arguments, but need at most %s", providedStringValues.size(), sourceMethod.getParameterTypes().length));
			receivedOptions.add(new CliReceivedOptionData(cliOption.getKey(), convertProvidedOptionValues(providedStringValues, sourceMethod)));
			LOGGER.debug("\tconverted option values: {}", getLast(receivedOptions).getProvidedOptionValues());
        }
        
        return new CliReceivedCommand(matchedCommand, receivedOptions);
    }
	
	private static Map<CliDeclaredOptionSpec, OptionSpec> matchProvidedOptions(Iterable<CliDeclaredOptionSpec> declaredOptions, List<OptionSpec> providedOptions) {
		Map<CliDeclaredOptionSpec, OptionSpec> matchedProvidedOptions = new LinkedHashMap<>();
		
		for (OptionSpec providedOption : providedOptions) {
			for (CliDeclaredOptionSpec declaredOption : declaredOptions) {
				if (providedOption.longestName().equals(declaredOption.getName())) {
					matchedProvidedOptions.put(declaredOption, providedOption);
				}
			}
		}
        return matchedProvidedOptions;
    }

	static List<Object> convertProvidedOptionValues(List<String> providedStringValues, Method m) {
		List<Object> providedValuesConverted = new ArrayList<>();
		
		final Annotation[][] annotations = m.getParameterAnnotations();
		final Class<?>[] declaredParameters = m.getParameterTypes();
		int mandatoryParameters = MiscUtil.countMandatoryParameters(m);
		
		for (int i = 0; i < declaredParameters.length; i++) {
			final boolean required = !containsAnnotation(asList(annotations[i]), Nullable.class);
			Object providedValueConverted = null;
			if (required || providedStringValues.size() > mandatoryParameters) {
				providedValueConverted = parseStringInput(providedStringValues.remove(0), declaredParameters[i]);
			}
			providedValuesConverted.add(providedValueConverted);
			mandatoryParameters = required ? mandatoryParameters - 1 : mandatoryParameters;
		}
		
		return providedValuesConverted;
	}
    
    private static Object parseStringInput(@NotNull String stringValue, @NotNull Class<?> targetType) {
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