package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.internal.clisupport.model.CliCommandType;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.lang.String.format;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.colorizeOptionsInText;
import static org.simplejavamail.internal.util.ListUtil.getFirst;

class CliCommandLineProducer {
    
    static final String OPTION_HELP_POSTFIX = "--help";
    static final String EMPTY_PARAM_LABEL = "<empty>";
    
    @SuppressWarnings("SameParameterValue")
    static CommandLine configurePicoCli(List<CliDeclaredOptionSpec> declaredOptions, int textWidth) {
        CommandSpec rootCommandsHolder = createDefaultCommandSpec("SimpleJavaMail",
                "Simple Java Mail Command Line Interface.%n" +
                        "%n" +
                        "All commands and their options are a direct translation of the Simple Java Mail builder API and translate back into builder calls " +
                        "(as such, the order of --options matter as well as combinations). Furthermore, all documentation is taken from the " +
                        "builder API Javadoc. Basically you configure builders just like you would in Java, but with CLI commands.%n" +
                        "%n" +
                        "Note that each and every @|yellow --option|@ has an @|yellow --option--help|@ variation for full documentation." +
                        "%n" +
                        "Note: All the regular functionality regarding properties and config files work with the CLI so you can provide defaults in a " +
                        "property file as long as it is visible (on class path) or as system environment variables.")
                .version("Simple Java Mail 6.0.0");
        
        rootCommandsHolder.usageMessage()
                .customSynopsis("",
                        colorizeOptionsInText("\tsend     [--help -h, --version -v] --email:options --mailer:options", "yellow"),
                        colorizeOptionsInText("\tconnect  [--help -h, --version -v] --mailer:options", "yellow"),
                        colorizeOptionsInText("\tvalidate [--help -h, --version -v] --email:options", "yellow"),
                        colorizeOptionsInText("\tconvert  [--help -h, --version -v] --email:options", "yellow"));
        
        createRootCommand(rootCommandsHolder, "send", "Send an email: starting blank, replying to or forwarding another email",
                                                                                        colorizeOptionsInText("\tsend [--help -h, --version -v] --email:options --mailer:options", "yellow"), declaredOptions);
        createRootCommand(rootCommandsHolder, "connect", "Test a server connection",    colorizeOptionsInText("\tconnect [--help -h, --version -v] --mailer:options", "yellow"), declaredOptions);
        createRootCommand(rootCommandsHolder, "validate", "Validate an email",          colorizeOptionsInText("\tvalidate [--help -h, --version -v] --email:options --mailer:options", "yellow"), declaredOptions);
        createRootCommand(rootCommandsHolder, "convert", "Convert between email types", colorizeOptionsInText("\tconvert [--help -h, --version -v] --email:options", "yellow"), declaredOptions);
        
        return new CommandLine(rootCommandsHolder).setUsageHelpWidth(textWidth).setSeparator(" ");
    }
    
    private static void createRootCommand(CommandSpec rootCommandsHolder, String name, String description, String synopsis,
                                          List<CliDeclaredOptionSpec> declaredOptions) {
        final CommandSpec rootCommand = createDefaultCommandSpec(name, description);
		final CliCommandType cliCommandType = CliCommandType.valueOf(rootCommand.name());
		final Collection<CliBuilderApiType> compatibleBuilderApiTypes = CliBuilderApiType.findForCliSynopsis(synopsis);
        rootCommand.usageMessage().customSynopsis(synopsis);
		populateRootCommands(rootCommand, declaredOptions, cliCommandType, compatibleBuilderApiTypes);
        rootCommandsHolder.addSubcommand(rootCommand.name(), rootCommand);
    }
    
    private static void populateRootCommands(CommandSpec rootCommand, List<CliDeclaredOptionSpec> declaredOptions, CliCommandType cliCommandType, Collection<CliBuilderApiType> compatibleBuilderApiTypes) {
		for (CliDeclaredOptionSpec cliDeclaredOptionSpec : declaredOptions) {
            if (cliDeclaredOptionSpec.applicableToRootCommand(cliCommandType, compatibleBuilderApiTypes)) {
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
        final List<String> descriptions = formatOptionDescription(cliCommand);
        if (!fullDescription && descriptions.size() > 1) {
            return new String[] {getFirst(descriptions) + " (...more)"};
        } else {
            return descriptions.toArray(new String[]{});
        }
    }
    
    @Nonnull
    private static List<String> formatOptionDescription(CliDeclaredOptionSpec cliOption) {
        final List<String> fullDescription = new ArrayList<>(cliOption.getDescription());
        if (!cliOption.getPossibleOptionValues().isEmpty()) {
            fullDescription.add("%n@|bold,underline Parameters|@:");
            for (CliDeclaredOptionValue possibleParam : cliOption.getPossibleOptionValues()) {
				String optionalInfix = !possibleParam.isRequired() ? " (optional)" : "";
				fullDescription.add(format("@|yellow %s%s|@: %s", possibleParam.getName(), optionalInfix, possibleParam.formatDescription()));
            }
        }
	
		List<String> seeAlsoReferences = TherapiJavadocHelper.getJavadocSeeAlsoReferences(cliOption.getSourceMethod());
        if (!seeAlsoReferences.isEmpty()) {
            fullDescription.add("%n@|bold,underline See also|@:");
			fullDescription.addAll(seeAlsoReferences);
        }
        return fullDescription;
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
