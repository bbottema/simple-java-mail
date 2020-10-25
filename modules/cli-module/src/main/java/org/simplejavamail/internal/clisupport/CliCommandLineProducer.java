package org.simplejavamail.internal.clisupport;

import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.lang.String.format;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.colorizeOptionsInText;
import static org.simplejavamail.internal.clisupport.CliColorScheme.COMMAND_OPTION_STYLE;
import static org.simplejavamail.internal.clisupport.CliColorScheme.OPTION_VALUE_STYLE;
import static org.simplejavamail.internal.util.ListUtil.getFirst;

class CliCommandLineProducer {
    
    static final String OPTION_HELP_POSTFIX = "--help";
    static final String EMPTY_PARAM_LABEL = "<empty>";
    
    @SuppressWarnings("SameParameterValue")
    static CommandLine configurePicoCli(List<CliDeclaredOptionSpec> declaredOptions, int maxTextWidth) {
        CommandSpec rootCommandsHolder = createDefaultCommandSpec("SimpleJavaMail",
                "Simple Java Mail Command Line Interface.%n" +
                        "%n" +
                        "All commands and their options are a direct translation of the Simple Java Mail builder API and translate back into builder calls " +
                        "(as such, the order of --options matter as well as combinations). Furthermore, all documentation is taken from the " +
                        "builder API Javadoc. Essentially you configure builders just like you would in Java, but with CLI commands.%n" +
                        "%n" +
                        "@|bold Note that each and every |@@|"+COMMAND_OPTION_STYLE+",bold --option|@ @|bold has an |@@|"+COMMAND_OPTION_STYLE+",bold --option--help|@ @|bold variation for full documentation.|@" +
                        "%n" +
                        "Note: All the regular functionality regarding properties and config files work with the CLI so you can provide defaults in a " +
                        "property file as long as it is visible (on class path) or as system environment variables.")
                .version("Simple Java Mail 6.4.4");
        
        rootCommandsHolder.usageMessage()
                .customSynopsis("",
                        colorizeOptionsInText("\tsend     [--help -h, --version -v] --email:options --mailer:options", COMMAND_OPTION_STYLE),
                        colorizeOptionsInText("\tconnect  [--help -h, --version -v] --mailer:options", COMMAND_OPTION_STYLE),
                        colorizeOptionsInText("\tvalidate [--help -h, --version -v] --email:options", COMMAND_OPTION_STYLE));
    
        createRootCommand(rootCommandsHolder, "send", "Send an email: starting blank, replying to or forwarding another email.",
                colorizeOptionsInText("\tsend [--help -h, --version -v] --email:options --mailer:options", COMMAND_OPTION_STYLE), declaredOptions, maxTextWidth);
        createRootCommand(rootCommandsHolder, "connect", "Test a server connection, including possible authentication and any proxy settings",
                colorizeOptionsInText("\tconnect [--help -h, --version -v] --mailer:options", COMMAND_OPTION_STYLE), declaredOptions, maxTextWidth);
        createRootCommand(rootCommandsHolder, "validate", "Validate an email for mandatory fields, injection detection and optional email address validation",
                colorizeOptionsInText("\tvalidate [--help -h, --version -v] --email:options --mailer:options", COMMAND_OPTION_STYLE), declaredOptions, maxTextWidth);
        
        return new CommandLine(rootCommandsHolder).setUsageHelpWidth(maxTextWidth).setSeparator(" ");
    }
    
    private static void createRootCommand(CommandSpec rootCommandsHolder, String name, String description, String synopsis,
                                                 List<CliDeclaredOptionSpec> declaredOptions, int maxTextWidth) {
        final CommandSpec rootCommand = createDefaultCommandSpec(name, description);
		final Collection<CliBuilderApiType> compatibleBuilderApiTypes = CliBuilderApiType.findForCliSynopsis(synopsis);
        rootCommand.usageMessage().customSynopsis(synopsis);
		populateRootCommands(rootCommand, declaredOptions, compatibleBuilderApiTypes, maxTextWidth);
        rootCommandsHolder.addSubcommand(rootCommand.name(), rootCommand);
    }
    
    private static void populateRootCommands(CommandSpec rootCommand, List<CliDeclaredOptionSpec> declaredOptions, Collection<CliBuilderApiType> compatibleBuilderApiTypes, int maxTextWidth) {
		for (CliDeclaredOptionSpec cliDeclaredOptionSpec : declaredOptions) {
            if (cliDeclaredOptionSpec.applicableToRootCommand(compatibleBuilderApiTypes)) {
                rootCommand.addOption(OptionSpec.builder(cliDeclaredOptionSpec.getName())
                        .type(List.class)
                        .auxiliaryTypes(String.class)
                        .arity(format("%s..%s",
                                cliDeclaredOptionSpec.getMandatoryOptionValues().size(),
                                cliDeclaredOptionSpec.getPossibleOptionValues().size()))
                        .paramLabel(determineParamLabel(cliDeclaredOptionSpec.getPossibleOptionValues()))
                        .hideParamSyntax(true)
                        .description(determineDescription(cliDeclaredOptionSpec, false, maxTextWidth))
                        .build());
                rootCommand.addOption(OptionSpec.builder(cliDeclaredOptionSpec.getName() + OPTION_HELP_POSTFIX)
                        .type(List.class) // cannot use .usageHelp(true), because this cannot be boolean, because description
                        .auxiliaryTypes(String.class)
                        .arity("*")
                        .hidden(true)
                        .help(true)
                        .paramLabel(determineParamLabel(cliDeclaredOptionSpec.getPossibleOptionValues()))
                        .hideParamSyntax(true)
                        .description(determineDescription(cliDeclaredOptionSpec, true, maxTextWidth))
                        .build());
            }
        }
    }
    
    // hide multi-line descriptions when usage is not focussed on the current option (ie. --current-option--help)
    private static String[] determineDescription(CliDeclaredOptionSpec cliCommand, boolean fullDescription, int maxTextWidth) {
        final List<String> descriptions = formatOptionDescription(cliCommand, maxTextWidth);
        if (!fullDescription && descriptions.size() > 1) {
            return new String[] {getFirst(descriptions) + " (...more)"};
        } else {
            return descriptions.toArray(new String[]{});
        }
    }
    
    @NotNull
    private static List<String> formatOptionDescription(CliDeclaredOptionSpec cliOption, int maxTextWidth) {
        final List<String> fullDescription = new ArrayList<>(cliOption.getDescription());
        if (!cliOption.getPossibleOptionValues().isEmpty()) {
            fullDescription.add("%n@|bold,underline Parameters|@:");
            for (CliDeclaredOptionValue possibleValue : cliOption.getPossibleOptionValues()) {
				String optionalInfix = !possibleValue.isRequired() ? " (optional->null)" : "";
				fullDescription.add(format("@|%s %s%s|@: %s", OPTION_VALUE_STYLE, possibleValue.getName(), optionalInfix, possibleValue.formatDescription()));
            }
        }
	
		List<String> seeAlsoReferences = TherapiJavadocHelper.getJavadocSeeAlsoReferences(cliOption.getSourceMethod(), true, maxTextWidth);
        if (!seeAlsoReferences.isEmpty()) {
            fullDescription.add("%n@|bold,underline See also|@:");
			fullDescription.addAll(seeAlsoReferences);
        }
        return fullDescription;
    }
    
    private static String determineParamLabel(List<CliDeclaredOptionValue> possibleParams) {
        final StringBuilder paramLabel = new StringBuilder();
        for (CliDeclaredOptionValue possibleParam : possibleParams) {
            paramLabel.append(possibleParam.isRequired() ? "" : "[");
            paramLabel.append(possibleParam.getName()).append("(=").append(possibleParam.getHelpLabel()).append(")");
            paramLabel.append(possibleParam.isRequired() ? " " : "] ");
        }
        String declaredParamLabel = paramLabel.toString().trim();
        return declaredParamLabel.isEmpty() ? EMPTY_PARAM_LABEL : declaredParamLabel;
    }
    
    private static CommandSpec createDefaultCommandSpec(@NotNull String name, @Nullable String... descriptions) {
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
