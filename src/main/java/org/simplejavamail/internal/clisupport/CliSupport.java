package org.simplejavamail.internal.clisupport;

import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.MailerFromSessionBuilder;
import picocli.CommandLine;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import static org.simplejavamail.internal.clisupport.BuildToCliOptionsMapper.generateOptionsNetwork;
import static picocli.CommandLine.Model.*;

public class CliSupport {
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API = {EmailBuilder.EmailBuilderInstance.class, MailerBuilder.MailerRegularBuilder.class, MailerFromSessionBuilder.class};
	
	public static void runCLI(String[] args) {
		Collection<CliOptionFlag> parameterMap = generateOptionsNetwork(RELEVANT_BUILDER_ROOT_API, new HashMap<Class<?>, Collection<CliOptionFlag>>());
		configurePicoCli(parameterMap);
		System.out.println(parameterMap);
	}
	
	private static CommandLine configurePicoCli(Collection<CliOptionFlag> parameterMap) {
		CommandSpec mainCommand = CommandSpec.create()
				.name("send")
				.version("6.0.0")
				.mixinStandardHelpOptions(true);
		configureOptionFlags(mainCommand, parameterMap);
		return new CommandLine(mainCommand);
	}
	
	private static void configureOptionFlags(CommandSpec spec, Collection<CliOptionFlag> parameterMap) {
		for (CliOptionFlag cliOptionFlag : parameterMap) {
			CommandSpec cmd = CommandSpec.create();
			cmd.addOption(OptionSpec.builder("-" + cliOptionFlag.getName())
					.paramLabel(cliOptionFlag.getHelpLabel())
					.type(int.class)
					.description("number of times to execute").build());
		}
		
		spec.addOption(OptionSpec.builder("-c", "--count")
				.paramLabel("COUNT")
				.type(int.class)
				.description("number of times to execute").build());
		spec.addPositional(PositionalParamSpec.builder()
				.paramLabel("FILES")
				.type(List.class)
				.auxiliaryTypes(File.class)
				.description("The files to process").build());
		
		CommandSpec subCmd = CommandSpec.create();
		subCmd.addOption(OptionSpec.builder("-b", "--blah")
				.paramLabel("BLAH")
				.type(String.class)
				.description("number of moomoo's").build());
		
		spec.addSubcommand("moo", subCmd);
	}
}
