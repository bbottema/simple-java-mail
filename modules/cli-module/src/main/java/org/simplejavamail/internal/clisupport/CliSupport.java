/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.clisupport;

import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.util.ArrayList;
import java.util.List;

import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi;
import static org.simplejavamail.internal.clisupport.CliCommandLineProducer.configurePicoCli;

public class CliSupport {
	
	private static final int CONSOLE_TEXT_WIDTH = 150;
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API 			= {EmailStartingBuilder.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class};
	private static final List<CliDeclaredOptionSpec> DECLARED_OPTIONS 	= generateOptionsFromBuilderApi(RELEVANT_BUILDER_ROOT_API);
	private static final CommandLine PICOCLI_COMMAND_LINE 				= configurePicoCli(DECLARED_OPTIONS, CONSOLE_TEXT_WIDTH);

	public static void runCLI(String[] args) {
		ParseResult pr = PICOCLI_COMMAND_LINE.parseArgs(cutOffAtHelp(args));
		
		if (!CliCommandLineConsumerUsageHelper.processAndApplyHelp(pr, CONSOLE_TEXT_WIDTH)) {
			CliReceivedCommand cliReceivedOptionData = CliCommandLineConsumer.consumeCommandLineInput(pr, DECLARED_OPTIONS);
			CliCommandLineConsumerResultHandler.processCliResult(cliReceivedOptionData);
		}
	}
	
	public static void listUsagesForAllOptions() {
		for (CliDeclaredOptionSpec declaredOption : DECLARED_OPTIONS) {
			runCLI(new String[]{"send", declaredOption.getName() + "--help"});
			System.out.print("\n\n\n");
		}
	}
	
	/**
	 * This is needed to avoid picocli to error out on other --params that are misconfigured.
	 */
	private static String[] cutOffAtHelp(String[] args) {
		List<String> argsToKeep = new ArrayList<>();
		for (String arg : args) {
			argsToKeep.add(arg);
			if (arg.endsWith(CliCommandLineProducer.OPTION_HELP_POSTFIX)) {
				break;
			}
		}
		
		if (argsToKeep.isEmpty()) {
			argsToKeep.add("--help");
		}
		
		return argsToKeep.toArray(new String[]{});
	}
}