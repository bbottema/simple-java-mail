package org.simplejavamail.internal.clisupport;

import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.clisupport.model.CliCommandData;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.MailerFromSessionBuilder;
import picocli.CommandLine;

import java.util.Collection;
import java.util.HashMap;
import java.util.TreeSet;

import static org.simplejavamail.internal.clisupport.BuilderApiToCliCommandsMapper.generateCommandsAndSubcommands;
import static org.simplejavamail.internal.clisupport.CliCommandLineHelpInputProcessor.helpProcessedAndApplied;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.configurePicoCli;

public class CliSupport {
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API = {EmailBuilder.EmailBuilderInstance.class, MailerBuilder.MailerRegularBuilder.class, MailerFromSessionBuilder.class};

	private static final int TEXT_WIDTH = 150;
	
	public static void runCLI(String[] args) {
		TreeSet<CliCommandData> parameterMap = generateCommandsAndSubcommands(RELEVANT_BUILDER_ROOT_API, new HashMap<Class<?>, Collection<CliCommandData>>());
		CommandLine.ParseResult pr = configurePicoCli(parameterMap, TEXT_WIDTH).parseArgs(args);
		
		if (!helpProcessedAndApplied(pr, TEXT_WIDTH)) {
		
		}
	}
}