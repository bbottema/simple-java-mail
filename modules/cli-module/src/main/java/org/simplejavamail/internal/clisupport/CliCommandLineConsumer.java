package org.simplejavamail.internal.clisupport;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueConversionHelper;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliCommandType;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.recipient.internal.MailboxInterpretation;
import org.simplejavamail.internal.util.MiscUtil;
import org.slf4j.Logger;
import picocli.CommandLine.ParseResult;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.bbottema.javareflection.TypeUtils.containsAnnotation;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Converts a successfully parsed, non-help Picocli command into ordered builder invocations.
 * Each supplied occurrence retains its own arguments before conversion to the exact builder parameter types.
 */
class CliCommandLineConsumer {
    
    private static final Logger LOGGER = getLogger(CliCommandLineConsumer.class);
    
    // we reach here when terminal input was value and no help was requested
    static CliReceivedCommand consumeCommandLineInput(ParseResult providedCommand, @SuppressWarnings("SameParameterValue") Iterable<CliDeclaredOptionSpec> declaredOptions) {
        assumeTrue(providedCommand.hasSubcommand(), "Command was empty, expected one of: " + Arrays.toString(CliCommandType.values()));
        
        final ParseResult mailCommand = providedCommand.subcommand();
        final CliCommandType matchedCommand = CliCommandType.valueOf(mailCommand.commandSpec().name());
        final Map<String, CliDeclaredOptionSpec> declaredOptionsByName = new HashMap<>();
        for (CliDeclaredOptionSpec declaredOption : declaredOptions) {
            declaredOptionsByName.put(declaredOption.getName(), declaredOption);
        }
        final CliOptionOccurrences occurrences = (CliOptionOccurrences) mailCommand.commandSpec().userObject();
    
		LOGGER.debug("processing mail command: {}", matchedCommand);
		
        List<CliReceivedOptionData> receivedOptions = new ArrayList<>();
        for (CliOptionOccurrences.Occurrence occurrence : occurrences.occurrences()) {
            final CliDeclaredOptionSpec declaredOption = declaredOptionsByName.get(occurrence.optionName());
			final Method sourceMethod = declaredOption.getSourceMethod();
			final int mandatoryParameters = MiscUtil.countMandatoryParameters(sourceMethod);
			final List<String> providedStringValues = new ArrayList<>(occurrence.arguments());
            assumeTrue(providedStringValues.size() >= mandatoryParameters,
                    format("provided %s arguments for '%s', but need at least %s", providedStringValues.size(), declaredOption, mandatoryParameters));
            assumeTrue(providedStringValues.size() <= sourceMethod.getParameterTypes().length,
                    format("provided %s arguments for '%s', but need at most %s", providedStringValues.size(), declaredOption, sourceMethod.getParameterTypes().length));
			receivedOptions.add(new CliReceivedOptionData(declaredOption, convertProvidedOptionValues(providedStringValues, sourceMethod)));
        }
        
        return new CliReceivedCommand(matchedCommand, receivedOptions,
                mailCommand.matchedOptionValue(CliCommandLineProducer.AUTHENTICATE_OPTION, false));
    }
	
	static List<Object> convertProvidedOptionValues(List<String> providedStringValues, Method m) {
		List<Object> providedValuesConverted = new ArrayList<>();
		
		final Annotation[][] annotations = m.getParameterAnnotations();
		final Class<?>[] declaredParameters = m.getParameterTypes();
		int mandatoryParameters = MiscUtil.countMandatoryParameters(m);
		
		for (int i = 0; i < declaredParameters.length; i++) {
			final boolean required = !containsAnnotation(asList(annotations[i]), Cli.Optional.class);
			Object providedValueConverted = null;
			if (required || providedStringValues.size() > mandatoryParameters) {
				final String source = providedStringValues.remove(0);
				// Composed recipients already interpret complete lists. Pre-splitting here corrupts quoted names and groups.
				if (declaredParameters[i] == String[].class && CliEmailRecipientBuilder.class.isAssignableFrom(m.getDeclaringClass())) {
					providedValueConverted = new String[]{source};
				} else if (declaredParameters[i] == String[].class && ExactEmailBuilder.class.isAssignableFrom(m.getDeclaringClass())
						&& m.getName().equals("withEnvelopeRecipients")) {
					providedValueConverted = MailboxInterpretation.splitEnvelopeEntries(source);
				} else {
					providedValueConverted = parseStringInput(source, declaredParameters[i]);
				}
			}
			providedValuesConverted.add(providedValueConverted);
			mandatoryParameters = required ? mandatoryParameters - 1 : mandatoryParameters;
		}
		
		return providedValuesConverted;
	}
    
    private static Object parseStringInput(@NotNull String stringValue, @NotNull Class<?> targetType) {
		if (targetType == String[].class) {
			return MiscUtil.extractEmailAddresses(stringValue);
		}
		try {
			// Parse the declared numeric width directly; a conversion through Integer loses fractions or overflows replay-byte limits.
			if (targetType == long.class || targetType == Long.class) {
				return Long.valueOf(stringValue);
			}
			if (targetType == double.class || targetType == Double.class) {
				return Double.valueOf(stringValue);
			}
			return ValueConversionHelper.convert(stringValue, targetType);
		} catch (IncompatibleTypeException | NumberFormatException e) {
			LOGGER.error("Unable to parse one command-line value as {}", targetType.getName());
			throw new CliExecutionException("Unable to parse a command-line value as " + targetType.getSimpleName(), e);
		}
	}
    
}
