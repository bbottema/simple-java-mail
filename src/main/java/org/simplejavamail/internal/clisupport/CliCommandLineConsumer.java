package org.simplejavamail.internal.clisupport;

import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.clisupport.annotation.CliCommand;
import org.simplejavamail.internal.clisupport.model.CliOptionData;
import org.simplejavamail.internal.util.ReflectiveValueConverter;
import org.slf4j.Logger;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import javax.annotation.Nonnull;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.slf4j.LoggerFactory.getLogger;

class CliCommandLineConsumer {
    
    private static final Logger LOGGER = getLogger(CliCommandLineConsumer.class);
    
    // we reach here when terminal input was value and no help was requested
    static void consumeCommandLineInput(ParseResult providedCommand, @SuppressWarnings("SameParameterValue") Set<CliOptionData> declaredOptions) {
        assumeTrue(providedCommand.hasSubcommand(), "Command was empty, expected one of: " + Arrays.toString(CliCommand.values()));
        
        final ParseResult mailCommand = providedCommand.subcommand();
        final CliCommand matchedCommand = CliCommand.valueOf(mailCommand.commandSpec().name());
        final TreeMap<CliOptionData, OptionSpec> matchedOptionsInOrderProvision = matchProvidedOptions(declaredOptions, matchedCommand, mailCommand.matchedOptions());
    
        logParsedInput(matchedCommand, matchedOptionsInOrderProvision);
    
        for (Entry<CliOptionData, OptionSpec> cliOption : matchedOptionsInOrderProvision.entrySet()) {
            Method m = cliOption.getKey().getSourceMethod();
            List<String> providedStringValues = cliOption.getValue().getValue();
            Class<?>[] expectedTypes = m.getParameterTypes();
            assumeTrue(providedStringValues.size() == expectedTypes.length,
                    format("provided %s arguments, but need %s", providedStringValues.size(), expectedTypes.length));
            List<Object> providedValuesConverted = new ArrayList<>();
    
            for (int i = 0; i < providedStringValues.size(); i++) {
                providedValuesConverted.add(parseStringInput(providedStringValues.get(i), expectedTypes[i]));
            }
            
            LOGGER.debug("\tconverted option values: {}", providedValuesConverted);
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
    
    private static Object parseStringInput(@Nonnull String stringValue, @Nonnull Class<?> targetType) {
        if (ReflectiveValueConverter.isCommonType(targetType)) {
            return ReflectiveValueConverter.convert(stringValue, targetType);
        } else if (targetType == MimeMessage.class) {
            // assume stringValue is a filepath
            if (stringValue.endsWith(".msg")) {
                return EmailConverter.outlookMsgToMimeMessage(new File(stringValue));
            } else if (stringValue.endsWith(".eml")) {
                return EmailConverter.emlToMimeMessage(new File(stringValue));
            } else {
                throw new CliExecutionException(format(CliExecutionException.DID_NOT_RECOGNIZE_EMAIL_FILETYPE, stringValue));
            }
        } else {
            throw new AssertionError(format("unexepcted targettype %s for input value %s", targetType, stringValue));
        }
    }
    
    private static void logParsedInput(CliCommand matchedCommand, TreeMap<CliOptionData, OptionSpec> matchedOptionsInOrderProvision) {
        LOGGER.debug("processing mail command: {}", matchedCommand);
        for (Entry<CliOptionData, OptionSpec> cliOption : matchedOptionsInOrderProvision.entrySet()) {
            CliOptionData declaredOption = cliOption.getKey();
            OptionSpec providedOption = cliOption.getValue();
            Collection<String> values = providedOption.getValue();
            LOGGER.debug("\tgot option: {}, with {} value(s): {}", declaredOption.getName(), values.size(), values);
        }
    }
}