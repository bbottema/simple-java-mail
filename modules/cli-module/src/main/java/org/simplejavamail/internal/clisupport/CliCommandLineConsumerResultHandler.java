package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Turns one parsed CLI command into the corresponding Email/Mailer builder flow and waits for its terminal result.
 * Mailer ownership stays outside this class: one-shot execution receives a close-after-command provider while daemon
 * execution receives a profile-keyed lease, keeping send, validate, and connection-test semantics identical.
 */
class CliCommandLineConsumerResultHandler {

	private static final Logger LOGGER = getLogger(CliCommandLineConsumerResultHandler.class);

	static void executeReceivedCommand(final CliReceivedCommand cliReceivedCommand,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		LOGGER.debug("invoking Builder API in order of provided options...");

		final List<CliReceivedOptionData> receivedOptions = cliReceivedCommand.getReceivedOptions();
		switch (cliReceivedCommand.getMatchedCommand()) {
			case send -> sendEmail(receivedOptions, environment, profileKey);
			case validate -> validateEmail(receivedOptions, environment, profileKey);
			case connect -> testConnection(receivedOptions, environment, profileKey);
		}
	}

	private static void sendEmail(final List<CliReceivedOptionData> receivedOptions,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		final EmailPopulatingBuilder emailBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.EMAIL,
				environment.simpleJavaMail().emailBuilder());
		final MailerGenericBuilder<?> mailerBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.MAILER,
				environment.simpleJavaMail().mailerBuilder());
		final Email email = emailBuilder.buildEmail();
		final CliMailerProfile profile = CliMailerProfile.create(environment.config(), receivedOptions, profileKey,
				environment.configurationWorkingDirectory());
		try (MailerProvider.Lease lease = environment.mailerProvider().acquire(profile, mailerBuilder::buildMailer)) {
			awaitCompletion(lease.mailer().sendMail(email), "sending email");
		}
	}

	private static void testConnection(final List<CliReceivedOptionData> receivedOptions,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		final MailerGenericBuilder<?> mailerBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.MAILER,
				environment.simpleJavaMail().mailerBuilder());
		final CliMailerProfile profile = CliMailerProfile.create(environment.config(), receivedOptions, profileKey,
				environment.configurationWorkingDirectory());
		try (MailerProvider.Lease lease = environment.mailerProvider().acquire(profile, mailerBuilder::buildMailer)) {
			final Mailer mailer = lease.mailer();
			awaitCompletion(mailer.testConnection(mailer.getOperationalConfig().isAsync()), "testing connection");
		}
	}

	private static void validateEmail(final List<CliReceivedOptionData> receivedOptions,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		final EmailPopulatingBuilder emailBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.EMAIL,
				environment.simpleJavaMail().emailBuilder());
		final MailerGenericBuilder<?> mailerBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.MAILER,
				environment.simpleJavaMail().mailerBuilder());
		final Email email = emailBuilder.buildEmail();
		final CliMailerProfile profile = CliMailerProfile.create(environment.config(), receivedOptions, profileKey,
				environment.configurationWorkingDirectory());
		try (MailerProvider.Lease lease = environment.mailerProvider().acquire(profile, mailerBuilder::buildMailer)) {
			lease.mailer().validate(email);
		}
	}

	private static void awaitCompletion(@NotNull final Future<Void> future, final String activity) {
		try {
			future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CliExecutionException("Interrupted while " + activity, e);
		} catch (ExecutionException e) {
			throw new CliExecutionException("Error while " + activity, e);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T applyBuilderOptions(final List<CliReceivedOptionData> receivedOptions,
			final CliBuilderApiType builderApiType, final Object initialBuilderInstance) {
		LOGGER.debug("\t{}", initialBuilderInstance.getClass().getSimpleName());
		Object currentBuilder = initialBuilderInstance;
		for (CliReceivedOptionData option : receivedOptions) {
			if (option.determineTargetBuilderApi() == builderApiType) {
				currentBuilder = invokeBuilderOption(currentBuilder, option);
			}
		}
		return (T) currentBuilder;
	}

	private static Object invokeBuilderOption(final Object currentBuilder, final CliReceivedOptionData option) {
		try {
			LOGGER.debug("\t\t.{}(<redacted>)", option.getDeclaredOptionSpec().getSourceMethod().getName());
			final Method sourceMethod = resolveDeserializedSourceMethod(option.getDeclaredOptionSpec().getSourceMethod());
			return sourceMethod.invoke(currentBuilder, option.getProvidedOptionValues().toArray());
		} catch (IllegalArgumentException e) {
			throw new CliExecutionException(
					formatCliInvocationError(CliExecutionException.WRONG_CURRENT_BUILDER, option), e);
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new CliExecutionException(
					formatCliInvocationError(CliExecutionException.ERROR_INVOKING_BUILDER_API, option), e);
		} catch (NoSuchMethodException e) {
			throw new CliExecutionException("This should never happen", e);
		}
	}

	/** Rebinds the deserialized method descriptor to the reflection object owned by this JVM. */
	@NotNull
	private static Method resolveDeserializedSourceMethod(@NotNull final Method sourceMethod) throws NoSuchMethodException {
		return sourceMethod.getDeclaringClass().getDeclaredMethod(sourceMethod.getName(), sourceMethod.getParameterTypes());
	}

	private static String formatCliInvocationError(final String exceptionTemplate, final CliReceivedOptionData option) {
		return format(exceptionTemplate, "<redacted>", option.getDeclaredOptionSpec().getName());
	}
}
