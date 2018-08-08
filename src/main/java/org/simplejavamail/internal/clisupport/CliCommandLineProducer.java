package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.annotation.CliCommand;
import org.simplejavamail.internal.clisupport.model.CliOptionData;
import org.simplejavamail.internal.clisupport.model.CliOptionValueData;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static java.lang.String.format;

class CliCommandLineProducer {
    
    static final String OPTION_HELP_POSTFIX = "--help";
    static final String EMPTY_PARAM_LABEL = "<empty>";
    
    @SuppressWarnings("SameParameterValue")
    static CommandLine configurePicoCli(TreeSet<CliOptionData> parameterMap, int textWidth) {
        CommandSpec rootCommandsHolder = createDefaultCommandSpec("SimpleJavaMail",
                "Simple Java Mail CliCommand Line Interface.%n" +
                        "%n" +
                        "All CLI support is a direct translation of the Simple Java Mail builder API and translates back into builder calls. " +
                        "As such, the @|bold order of directives matters as well as combinations|@! Furthermore, all documentation is taken from the " +
                        "builder API Javadoc.%n" +
                        "%n" +
                        "Note: All the regular functionality regarding properties and config files work with the CLI so you can provides defaults " +
                        "as long as they are visible (on class path).")
                .version("Simple Java Mail 6.0.0");
        
        rootCommandsHolder.usageMessage()
                .customSynopsis(
                        "\tsend     [options] email:options mailer:options",
                        "\tconnect  [options] mailer:options",
                        "\tvalidate [options] email:options mailer:options",
                        "\tconvert  [options] email:options");
        
        createRootCommand(rootCommandsHolder, "send", "Send an email, starting blank, replying to or forwarding another email", "\tsend [options] email:options mailer:options", parameterMap);
        createRootCommand(rootCommandsHolder, "connect", "Test a server connection", "\tconnect [options] mailer:options", parameterMap);
        createRootCommand(rootCommandsHolder, "validate", "Validate an email", "\tvalidate [options] email:options mailer:options", parameterMap);
        createRootCommand(rootCommandsHolder, "convert", "Convert between email types", "\tvalidate [options] email:options", parameterMap);
        
        return new CommandLine(rootCommandsHolder).setUsageHelpWidth(textWidth).setSeparator(" ");
    }
    
    private static void createRootCommand(CommandSpec rootCommandsHolder, String name, String description, String synopsis,
                                          TreeSet<CliOptionData> parameterMap) {
        CommandSpec rootCommand = createDefaultCommandSpec(name, description);
        rootCommand.usageMessage().customSynopsis(synopsis);
        populateRootCommands(rootCommand, parameterMap);
        rootCommandsHolder.addSubcommand(rootCommand.name(), rootCommand);
    }
    
    private static void populateRootCommands(CommandSpec rootCommand, TreeSet<CliOptionData> parameterMap) {
        for (CliOptionData cliOptionData : parameterMap) {
            if (cliOptionData.applicableToRootCommand(CliCommand.valueOf(rootCommand.name()))) {
                rootCommand.addOption(OptionSpec.builder(cliOptionData.getName())
                        .type(List.class)
                        .auxiliaryTypes(String.class)
                        .arity(String.valueOf(cliOptionData.getPossibleOptionValues().size()))
                        .paramLabel(determineParamLabel(cliOptionData.getPossibleOptionValues()))
                        .description(determineDescription(cliOptionData, false))
                        //.required(/*FIXME cliCommand.isRequired()*/)
                        .build());
                rootCommand.addOption(OptionSpec.builder(cliOptionData.getName() + OPTION_HELP_POSTFIX)
                        .type(List.class) // cannot use .usageHelp(true), because this cannot be boolean, because description
                        .auxiliaryTypes(String.class)
                        .arity("*")
                        .hidden(true)
                        .help(true)
                        .paramLabel(determineParamLabel(cliOptionData.getPossibleOptionValues()))
                        .description(determineDescription(cliOptionData, true))
                        .build());
            }
        }
    }
    
    // hide multi-line descriptions when usage is not focussed on the current option (ie. --current-option--help)
    private static String[] determineDescription(CliOptionData cliCommand, boolean fullDescription) {
        final List<String> descriptions = formatCommandDescriptions(cliCommand);
        if (!fullDescription && descriptions.size() > 1) {
            return new String[] {descriptions.get(0) + " (...more)"};
        } else {
            return descriptions.toArray(new String[]{});
        }
    }
    
    @Nonnull
    private static List<String> formatCommandDescriptions(CliOptionData cliCommand) {
        final List<String> descriptions = new ArrayList<>(cliCommand.getDescription());
        if (!cliCommand.getPossibleOptionValues().isEmpty()) {
            descriptions.add("%n@|bold,underline Parameters|@:");
            for (CliOptionValueData possibleParam : cliCommand.getPossibleOptionValues()) {
                descriptions.add(format("@|yellow %s|@: %s", possibleParam.getName(), possibleParam.formatDescription()));
            }
        }
        return descriptions;
    }
    
    private static String determineParamLabel(List<CliOptionValueData> possibleParams) {
        final StringBuilder paramLabel = new StringBuilder();
        for (CliOptionValueData possibleParam : possibleParams) {
            paramLabel.append(possibleParam.getName()).append("(=").append(possibleParam.getHelpLabel()).append(") ");
        }
        String declaredParamLabel = paramLabel.toString().trim();
        return declaredParamLabel.isEmpty() ? EMPTY_PARAM_LABEL : declaredParamLabel;
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
