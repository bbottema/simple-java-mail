package org.simplejavamail.internal.clisupport;

import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

class CliCommandLineConsumerResultHandler {
	
	private static final Logger LOGGER = getLogger(CliCommandLineConsumerResultHandler.class);
	
	static void processCliResult(CliReceivedCommand cliReceivedCommand) {
		LOGGER.debug("invoking Builder API in order of provided options...");
		
		final List<CliReceivedOptionData> receivedOptions = cliReceivedCommand.getReceivedOptions();
		
		switch (cliReceivedCommand.getMatchedCommand()) {
			case send: processCliSend(receivedOptions); break;
			case validate: processCliValidate(receivedOptions); break;
			case connect: processCliTestConnection(receivedOptions); break;
		}
	}
	
	@SuppressWarnings("deprecation")
	private static void processCliSend(List<CliReceivedOptionData> receivedOptions) {
		final EmailPopulatingBuilder emailBuilder = invokeBuilderApi(receivedOptions, CliBuilderApiType.EMAIL, new EmailStartingBuilderImpl());
		final MailerGenericBuilder<?> mailerBuilder = invokeBuilderApi(receivedOptions, CliBuilderApiType.MAILER, new MailerRegularBuilderImpl());
		mailerBuilder.buildMailer().sendMail(emailBuilder.buildEmail());
	}
	
	@SuppressWarnings("deprecation")
	private static void processCliTestConnection(List<CliReceivedOptionData> receivedOptions) {
		final MailerGenericBuilder<?> mailerBuilder = invokeBuilderApi(receivedOptions, CliBuilderApiType.MAILER, new MailerRegularBuilderImpl());
		mailerBuilder.buildMailer().testConnection();
	}
	
	@SuppressWarnings("deprecation")
	private static void processCliValidate(List<CliReceivedOptionData> receivedOptions) {
		final EmailPopulatingBuilder emailBuilder = invokeBuilderApi(receivedOptions, CliBuilderApiType.EMAIL, new EmailStartingBuilderImpl());
		final MailerGenericBuilder<?> mailerBuilder = invokeBuilderApi(receivedOptions, CliBuilderApiType.MAILER, new MailerRegularBuilderImpl());
		mailerBuilder.buildMailer().validate(emailBuilder.buildEmail());
	}
	
	@SuppressWarnings("unchecked")
	private static <T> T invokeBuilderApi(List<CliReceivedOptionData> cliReceivedOptionData, CliBuilderApiType builderApiType, Object initialBuilderInstance) {
		LOGGER.debug("\t{}", initialBuilderInstance.getClass().getSimpleName());
		Object currentBuilder = initialBuilderInstance;
		for (CliReceivedOptionData option : cliReceivedOptionData) {
			if (option.determineTargetBuilderApi() == builderApiType) {
				try {
					LOGGER.debug("\t\t.{}({})", option.getDeclaredOptionSpec().getSourceMethod().getName(), option.getProvidedOptionValues());
					currentBuilder = option.getDeclaredOptionSpec().getSourceMethod().invoke(currentBuilder, option.getProvidedOptionValues().toArray());
				} catch (IllegalArgumentException e) {
					throw new CliExecutionException(CliExecutionException.WRONG_CURRENT_BUILDER, e);
				} catch (IllegalAccessException | InvocationTargetException e) {
					throw new CliExecutionException(CliExecutionException.ERROR_INVOKING_BUILDER_API, e);
				}
			}
		}
		return (T) currentBuilder;
	}
}
