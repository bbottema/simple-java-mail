package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.slf4j.Logger;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Turns one parsed CLI command into the corresponding Email/Mailer builder flow and waits for its terminal result.
 * Mailer ownership stays outside this class: one-shot execution receives a close-after-command provider while daemon
 * execution receives a profile-keyed lease, keeping operation semantics identical across both routes.
 * Probe reports go to the request's output stream, never process-global stdout.
 */
class CliCommandLineConsumerResultHandler {

	private static final Logger LOGGER = getLogger(CliCommandLineConsumerResultHandler.class);

	static CliExitCode executeReceivedCommand(final CliReceivedCommand cliReceivedCommand,
			final CliExecutionEnvironment environment, final byte[] profileKey, final PrintStream out) {
		LOGGER.debug("invoking Builder API in order of provided options...");

		final List<CliReceivedOptionData> receivedOptions = cliReceivedCommand.getReceivedOptions();
		switch (cliReceivedCommand.getMatchedCommand()) {
			case send -> sendEmail(receivedOptions, environment, profileKey);
			case validate -> validateEmail(receivedOptions, environment, profileKey);
			case connect -> testConnection(receivedOptions, environment, profileKey);
			case probe -> {
				return probeConnection(receivedOptions, cliReceivedCommand.isAuthenticationRequested(), environment, profileKey, out);
			}
		}
		return CliExitCode.SUCCESS;
	}

	private static void sendEmail(final List<CliReceivedOptionData> receivedOptions,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		final Object selectedEmailBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.EMAIL,
				environment.simpleJavaMail().emailBuilder());
		final MailerGenericBuilder<?> mailerBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.MAILER,
				environment.simpleJavaMail().mailerBuilder());
		final Email email = buildSelectedEmail(selectedEmailBuilder);
		final CliMailerProfile profile = CliMailerProfile.create(environment.config(), receivedOptions, profileKey,
				environment.configurationWorkingDirectory());
		try (MailerProvider.Lease lease = environment.mailerProvider().acquire(profile, mailerBuilder::buildMailer)) {
			lease.mailer().sync().sendMail(email);
		}
	}

	private static void testConnection(final List<CliReceivedOptionData> receivedOptions,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		final MailerGenericBuilder<?> mailerBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.MAILER,
				environment.simpleJavaMail().mailerBuilder());
		final CliMailerProfile profile = CliMailerProfile.create(environment.config(), receivedOptions, profileKey,
				environment.configurationWorkingDirectory());
		try (MailerProvider.Lease lease = environment.mailerProvider().acquire(profile, mailerBuilder::buildMailer)) {
			lease.mailer().sync().testConnection();
		}
	}

	private static CliExitCode probeConnection(final List<CliReceivedOptionData> receivedOptions, final boolean authenticate,
			final CliExecutionEnvironment environment, final byte[] profileKey, final PrintStream out) {
		final MailerGenericBuilder<?> mailerBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.MAILER,
				environment.simpleJavaMail().mailerBuilder());
		final CliMailerProfile profile = CliMailerProfile.create(environment.config(), receivedOptions, profileKey,
				environment.configurationWorkingDirectory());
		try (MailerProvider.Lease lease = environment.mailerProvider().acquire(profile, mailerBuilder::buildMailer)) {
			final SmtpConnectionReport report = lease.mailer().sync().probeConnection(authenticate);
			out.println(report);
			return report.isSuccessful() ? CliExitCode.SUCCESS : CliExitCode.COMMAND_FAILED;
		}
	}

	private static void validateEmail(final List<CliReceivedOptionData> receivedOptions,
			final CliExecutionEnvironment environment, final byte[] profileKey) {
		final Object selectedEmailBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.EMAIL,
				environment.simpleJavaMail().emailBuilder());
		final MailerGenericBuilder<?> mailerBuilder = applyBuilderOptions(receivedOptions, CliBuilderApiType.MAILER,
				environment.simpleJavaMail().mailerBuilder());
		final Email email = buildSelectedEmail(selectedEmailBuilder);
		final CliMailerProfile profile = CliMailerProfile.create(environment.config(), receivedOptions, profileKey,
				environment.configurationWorkingDirectory());
		try (MailerProvider.Lease lease = environment.mailerProvider().acquire(profile, mailerBuilder::buildMailer)) {
			lease.mailer().validate(email);
		}
	}

	@NotNull
	private static Email buildSelectedEmail(@NotNull final Object selectedEmailBuilder) {
		if (selectedEmailBuilder instanceof EmailPopulatingBuilder) {
			return ((EmailPopulatingBuilder) selectedEmailBuilder).buildEmail();
		}
		if (selectedEmailBuilder instanceof ExactEmailBuilder) {
			return ((ExactEmailBuilder) selectedEmailBuilder).buildEmail();
		}
		throw new CliExecutionException(
				"The selected email options did not produce a completable email builder",
				new IllegalStateException("Unsupported email builder: " + selectedEmailBuilder.getClass().getName()));
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
