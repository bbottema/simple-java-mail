package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.lang.String.format;
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
		final Email email = emailBuilder.buildEmail();
		final Mailer mailer = mailerBuilder.buildMailer();
		try {
			waitFor(mailer.sendMail(email), "sending email");
		} finally {
			closeMailer(mailer, "sending email");
		}
	}
	
	@SuppressWarnings("deprecation")
	private static void processCliTestConnection(List<CliReceivedOptionData> receivedOptions) {
		final MailerGenericBuilder<?> mailerBuilder = invokeBuilderApi(receivedOptions, CliBuilderApiType.MAILER, new MailerRegularBuilderImpl());
		final Mailer mailer = mailerBuilder.buildMailer();
		try {
			waitFor(mailer.testConnection(mailer.getOperationalConfig().isAsync()), "testing connection");
		} finally {
			closeMailer(mailer, "testing connection");
		}
	}
	
	@SuppressWarnings("deprecation")
	private static void processCliValidate(List<CliReceivedOptionData> receivedOptions) {
		final EmailPopulatingBuilder emailBuilder = invokeBuilderApi(receivedOptions, CliBuilderApiType.EMAIL, new EmailStartingBuilderImpl());
		final MailerGenericBuilder<?> mailerBuilder = invokeBuilderApi(receivedOptions, CliBuilderApiType.MAILER, new MailerRegularBuilderImpl());
		final Email email = emailBuilder.buildEmailCompletedWithDefaultsAndOverrides();
		final Mailer mailer = mailerBuilder.buildMailer();
		try {
			mailer.validate(email);
		} finally {
			closeMailer(mailer, "validating email");
		}
	}

	private static void waitFor(@NotNull Future<Void> future, String activity) {
		try {
			future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CliExecutionException("Interrupted while " + activity, e);
		} catch (ExecutionException e) {
			throw new CliExecutionException("Error while " + activity, e);
		}
	}

	private static void closeMailer(@NotNull Mailer mailer, String activity) {
		try {
			mailer.close();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CliExecutionException("Interrupted while cleaning up after " + activity, e);
		} catch (Exception e) {
			throw new CliExecutionException("Error while cleaning up after " + activity, e);
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T> T invokeBuilderApi(List<CliReceivedOptionData> cliReceivedOptionData, CliBuilderApiType builderApiType, Object initialBuilderInstance) {
		LOGGER.debug("\t{}", initialBuilderInstance.getClass().getSimpleName());
		Object currentBuilder = initialBuilderInstance;
		for (CliReceivedOptionData option : cliReceivedOptionData) {
			if (option.determineTargetBuilderApi() == builderApiType) {
				try {
					LOGGER.debug("\t\t.{}({})", option.getDeclaredOptionSpec().getSourceMethod().getName(), option.getProvidedOptionValues());

					Method sourceMethod = determineTrueSourceMethod(option.getDeclaredOptionSpec().getSourceMethod());

					currentBuilder = sourceMethod.invoke(currentBuilder, option.getProvidedOptionValues().toArray());
				} catch (IllegalArgumentException e) {
					throw new CliExecutionException(formatCliInvocationError(CliExecutionException.WRONG_CURRENT_BUILDER, option), e);
				} catch (IllegalAccessException | InvocationTargetException e) {
					throw new CliExecutionException(formatCliInvocationError(CliExecutionException.ERROR_INVOKING_BUILDER_API, option), e);
				} catch (NoSuchMethodException e) {
					throw new CliExecutionException("This should never happen", e);
				}
			}
		}
		return (T) currentBuilder;
	}

	@NotNull
	// yeah, so after deserializing a java.lang.Method, it's actually a fake, so let's find it's real counter version
	private static Method determineTrueSourceMethod(@NotNull Method sourceMethod)
			throws NoSuchMethodException {
		return sourceMethod.getDeclaringClass().getDeclaredMethod(sourceMethod.getName(), sourceMethod.getParameterTypes());
	}

	private static String formatCliInvocationError(final String exceptionTemplate, final CliReceivedOptionData option) {
		return format(exceptionTemplate, option.getProvidedOptionValues(), option.getDeclaredOptionSpec().getName());
	}
}
