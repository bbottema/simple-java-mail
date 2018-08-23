package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.model.CliCommandType;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionValue;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.ListUtil.getFirst;

class CliCommandLineProducer {
    
    static final String OPTION_HELP_POSTFIX = "--help";
    static final String EMPTY_PARAM_LABEL = "<empty>";
    
    @SuppressWarnings("SameParameterValue")
    static CommandLine configurePicoCli(TreeSet<CliDeclaredOptionSpec> parameterMap, int textWidth) {
        CommandSpec rootCommandsHolder = createDefaultCommandSpec("SimpleJavaMail",
                "Simple Java Mail CliCommandType Line Interface.%n" +
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
                                          TreeSet<CliDeclaredOptionSpec> parameterMap) {
        CommandSpec rootCommand = createDefaultCommandSpec(name, description);
        rootCommand.usageMessage().customSynopsis(synopsis);
        populateRootCommands(rootCommand, parameterMap);
        rootCommandsHolder.addSubcommand(rootCommand.name(), rootCommand);
    }
    
    private static void populateRootCommands(CommandSpec rootCommand, TreeSet<CliDeclaredOptionSpec> parameterMap) {
        for (CliDeclaredOptionSpec cliDeclaredOptionSpec : parameterMap) {
            if (cliDeclaredOptionSpec.applicableToRootCommand(CliCommandType.valueOf(rootCommand.name()))) {
                rootCommand.addOption(OptionSpec.builder(cliDeclaredOptionSpec.getName())
                        .type(List.class)
                        .auxiliaryTypes(String.class)
                        .arity(String.valueOf(cliDeclaredOptionSpec.getPossibleOptionValues().size()))
                        .paramLabel(determineParamLabel(cliDeclaredOptionSpec.getPossibleOptionValues()))
                        .hideParamSyntax(true)
                        .description(determineDescription(cliDeclaredOptionSpec, false))
                        .build());
                rootCommand.addOption(OptionSpec.builder(cliDeclaredOptionSpec.getName() + OPTION_HELP_POSTFIX)
                        .type(List.class) // cannot use .usageHelp(true), because this cannot be boolean, because description
                        .auxiliaryTypes(String.class)
                        .arity("*")
                        .hidden(true)
                        .help(true)
                        .paramLabel(determineParamLabel(cliDeclaredOptionSpec.getPossibleOptionValues()))
                        .hideParamSyntax(true)
                        .description(determineDescription(cliDeclaredOptionSpec, true))
                        .build());
            }
        }
    }
    
    // hide multi-line descriptions when usage is not focussed on the current option (ie. --current-option--help)
    private static String[] determineDescription(CliDeclaredOptionSpec cliCommand, boolean fullDescription) {
        final List<String> descriptions = formatCommandDescriptions(cliCommand);
        if (!fullDescription && descriptions.size() > 1) {
            return new String[] {getFirst(descriptions) + " (...more)"};
        } else {
            return descriptions.toArray(new String[]{});
        }
    }
    
    @Nonnull
    private static List<String> formatCommandDescriptions(CliDeclaredOptionSpec cliCommand) {
        final List<String> descriptions = new ArrayList<>(cliCommand.getDescription());
        if (!cliCommand.getPossibleOptionValues().isEmpty()) {
            descriptions.add("%n@|bold,underline Parameters|@:");
            for (CliDeclaredOptionValue possibleParam : cliCommand.getPossibleOptionValues()) {
				String optionalInfix = !possibleParam.isRequired() ? " (optional)" : "";
				descriptions.add(format("@|yellow %s%s|@: %s", possibleParam.getName(), optionalInfix, possibleParam.formatDescription()));
            }
        }
        return descriptions;
    }
    
    private static String determineParamLabel(List<CliDeclaredOptionValue> possibleParams) {
        final StringBuilder paramLabel = new StringBuilder();
        for (CliDeclaredOptionValue possibleParam : possibleParams) {
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
