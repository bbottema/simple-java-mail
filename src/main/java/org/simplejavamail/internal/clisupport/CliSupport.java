package org.simplejavamail.internal.clisupport;

import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.clisupport.model.CliOptionData;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.MailerFromSessionBuilder;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.util.Collection;
import java.util.HashMap;
import java.util.TreeSet;

import static org.simplejavamail.internal.clisupport.BuilderApiToCliCommandsMapper.generateCommandsAndSubcommands;

public class CliSupport {
	
	private static final int TEXT_WIDTH = 150;
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API = {EmailBuilder.EmailBuilderInstance.class, MailerBuilder.MailerRegularBuilder.class, MailerFromSessionBuilder.class};
	private static final TreeSet<CliOptionData> COMMANDS_AND_OPTIONS = generateCommandsAndSubcommands(RELEVANT_BUILDER_ROOT_API, new HashMap<Class<?>, Collection<CliOptionData>>());
	private static final CommandLine COMMAND_LINE = CliCommandLineProducer.configurePicoCli(COMMANDS_AND_OPTIONS, TEXT_WIDTH);

	
	public static void runCLI(String[] args) {
		ParseResult pr = COMMAND_LINE.parseArgs(args);
		
		if (!CliCommandLineConsumerUsageHelper.processAndApplyHelp(pr, TEXT_WIDTH)) {
			CliCommandLineConsumer.consumeCommandLineInput(pr, COMMANDS_AND_OPTIONS);
		}
	}
}