package org.simplejavamail.internal.clisupport;

import jakarta.mail.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.OpenConnectionCallback;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.colorizeDescriptions;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.getArgumentsForCliOption;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.methodIsCliCompatible;

public class BuilderApiToPicocliCommandsMapperTest {

	@Test
	public void testColorizeDescriptions() {
		assertThat(colorizeDescriptions(singletonList("nothing to colorize"))).containsExactly("nothing to colorize");
		assertThat(colorizeDescriptions(singletonList("one --x:item to colorize"))).containsExactly("one @|cyan --x:item|@ to colorize");
		assertThat(colorizeDescriptions(singletonList("item @|--x:already|@ colorized"))).containsExactly("item @|--x:already|@ colorized");
		assertThat(colorizeDescriptions(singletonList("@|item @|--x:already|@ c|@olorized"))).containsExactly("@|item @|--x:already|@ c|@olorized");
		assertThat(colorizeDescriptions(singletonList("@|one @|--x:item|@ --x:to|@--x:colorize"))).containsExactly("@|one @|--x:item|@ --x:to|@@|cyan --x:colorize|@");
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyClosed() {
		final List<String> strings = new ArrayList<>();
		strings.add("@| |@ |@ @| |@");
		
		assertThatThrownBy(() -> colorizeDescriptions(strings))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("closed token without open token");
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyOpened() {
		final List<String> strings = new ArrayList<>();
		strings.add("@| |@ @| @| |@");
		
		assertThatThrownBy(() -> colorizeDescriptions(strings))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("open token without closed token");
	}
	
	@Test
	public void testExtractJavadocDescription_extractJavadocExamples() {
		final String description = "Single RFC2822 address or delimited list of RFC2822 addresses of TO receiver(s). Any names included are ignored if a name was provided.";
		final String examples = " Examples:  \n" +
				"  - lolly.pop@pretzelfun.com \n" +
				"  - Lolly Pop<lolly.pop@pretzelfun.com> \n" +
				"  - a1@b1.c1,a2@b2.c2,a3@b3.c3 \r\n" +
				"  - a1@b1.c1;a2@b2.c2;a3@b3.c3 ";
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description + examples)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description)).isEqualTo(new String[0]);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description + examples)).containsExactly(
				"lolly.pop@pretzelfun.com",
				"Lolly Pop<lolly.pop@pretzelfun.com>",
				"a1@b1.c1,a2@b2.c2,a3@b3.c3",
				"a1@b1.c1;a2@b2.c2;a3@b3.c3");
	}
	
	@Test
	public void testExtractJavadocDescription_extractJavadocExample() {
		final String description = "Single RFC2822 address or delimited list of RFC2822 addresses of TO receiver(s). Any names included are ignored if a name was provided.";
		final String example = " Example: lolly.pop@pretzelfun.com \n";
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description + example)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description)).isEqualTo(new String[0]);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description + example)).containsExactly("lolly.pop@pretzelfun.com");
	}

	@Test
	public void deliveryStatusNotificationBuilderApiUsesTypedCliConversions() throws Exception {
		for (final Class<?> builderType : new Class<?>[]{EmailPopulatingBuilder.class, ExactEmailBuilder.class}) {
			final Method notify = builderType.getMethod("withDeliveryStatusNotificationNotifyOptions", NotifyOption[].class);
			final Method returnOption = builderType.getMethod("withDeliveryStatusNotificationReturnOption", ReturnOption.class);
			assertThat(methodIsCliCompatible(notify).isCompatible()).isTrue();
			assertThat(methodIsCliCompatible(returnOption).isCompatible()).isTrue();
			assertThat(getArgumentsForCliOption(notify)).extracting("helpLabel").containsExactly("EVENTS");
			assertThat(getArgumentsForCliOption(returnOption)).extracting("helpLabel").containsExactly("NAME");
			assertThat(getArgumentsForCliOption(notify)).extracting("required").containsExactly(true);
			assertThat(getArgumentsForCliOption(returnOption)).extracting("required").containsExactly(true);
			assertThat(methodIsCliCompatible(builderType.getMethod("withDeliveryStatusNotification", DeliveryStatusNotification.class)).isCompatible()).isFalse();
			assertThatThrownBy(() -> builderType.getMethod("withDeliveryStatusNotificationNotifyOptions", String.class))
					.isInstanceOf(NoSuchMethodException.class);
			assertThatThrownBy(() -> builderType.getMethod("withDeliveryStatusNotificationReturnOption", String.class))
					.isInstanceOf(NoSuchMethodException.class);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"failure,delay", "FAILURE;DELAY", "notify_failure, notify_delay", "failure,delay,failure"})
	void notificationArrayConverterPreservesTextAliasesAndRemovesDuplicates(final String value) throws Exception {
		final Method notify = EmailPopulatingBuilder.class.getMethod("withDeliveryStatusNotificationNotifyOptions", NotifyOption[].class);
		assertThat(methodIsCliCompatible(notify).isCompatible()).isTrue();
		final List<Object> converted = CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(singletonList(value)), notify);
		assertThat((NotifyOption[]) converted.get(0)).containsExactly(NotifyOption.FAILURE, NotifyOption.DELAY);
	}

	@ParameterizedTest
	@CsvSource({"HDRS, HEADERS_ONLY", "headers only, HEADERS_ONLY", "RETURN_HDRS, HEADERS_ONLY", "FULL, FULL_MESSAGE", "full_message, FULL_MESSAGE"})
	void returnOptionConverterPreservesTextAliases(final String value, final ReturnOption expected) throws Exception {
		final Method returnOption = EmailPopulatingBuilder.class.getMethod("withDeliveryStatusNotificationReturnOption", ReturnOption.class);
		assertThat(methodIsCliCompatible(returnOption).isCompatible()).isTrue();
		assertThat(CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(singletonList(value)), returnOption)).containsExactly(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "unknown", "NEVER,FAILURE"})
	void notificationArrayConverterRejectsInvalidPreferences(final String value) throws Exception {
		final Method notify = EmailPopulatingBuilder.class.getMethod("withDeliveryStatusNotificationNotifyOptions", NotifyOption[].class);
		assertThat(methodIsCliCompatible(notify).isCompatible()).isTrue();
		assertThatThrownBy(() -> CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(singletonList(value)), notify))
				.isInstanceOf(CliExecutionException.class);
	}

	@Test
	void notificationArrayConverterRetainsExplicitNever() throws Exception {
		final Method notify = EmailPopulatingBuilder.class.getMethod("withDeliveryStatusNotificationNotifyOptions", NotifyOption[].class);
		assertThat(methodIsCliCompatible(notify).isCompatible()).isTrue();
		final List<Object> converted = CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(singletonList("never")), notify);
		assertThat((NotifyOption[]) converted.get(0)).containsExactly(NotifyOption.NEVER);
	}

	@Test
	public void asyncQueueSettingsHaveCliOptionsAndTypedArguments() throws Exception {
		final Method capacity = MailerGenericBuilder.class.getMethod("withAsyncQueueCapacity", int.class);
		final Method policy = MailerGenericBuilder.class.getMethod("withAsyncQueueOverflowPolicy", AsyncQueueOverflowPolicy.class);
		final Method timeout = MailerGenericBuilder.class.getMethod("withAsyncQueueWaitTimeoutMillis", int.class);
		for (Method setting : Arrays.asList(capacity, policy, timeout)) {
			assertThat(methodIsCliCompatible(setting).isCompatible()).isTrue();
			assertThat(getArgumentsForCliOption(setting)).extracting("required").containsExactly(true);
		}
		assertThat(CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(singletonList("WAIT_FOR_CAPACITY")), policy))
				.containsExactly(AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY);
		assertThat(CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(singletonList("0")), capacity)).containsExactly(0);
		assertThat(CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(singletonList("250")), timeout)).containsExactly(250);
		final List<CliDeclaredOptionSpec> options = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(
				new Class<?>[] {MailerRegularBuilder.class, MailerFromSessionBuilder.class});
		assertThat(options).extracting(CliDeclaredOptionSpec::getName)
				.contains("--mailer:withAsyncQueueCapacity", "--mailer:withAsyncQueueOverflowPolicy", "--mailer:withAsyncQueueWaitTimeoutMillis")
				.doesNotContain("--mailer:getAsyncQueueSnapshot", "--mailer:getAsyncQueueConfig", "--mailer:async", "--mailer:async--help");
	}

	@Test
	void totalTimeoutIsAValueOptionWhileObserverDispatchRemainsJavaOnly() throws Exception {
		final Method timeout = MailerGenericBuilder.class.getMethod("withMailSendTimeout", Duration.class);
		assertThat(methodIsCliCompatible(timeout).isCompatible()).isTrue();
		assertThat(CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(singletonList("PT12.5S")), timeout))
				.containsExactly(Duration.ofMillis(12500));
		assertThatThrownBy(() -> CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(singletonList("30 seconds")), timeout))
				.isInstanceOf(RuntimeException.class);
		assertThat(methodIsCliCompatible(MailerGenericBuilder.class.getMethod("withMailSendObserver", MailSendObserver.class)).isCompatible()).isFalse();
		assertThat(methodIsCliCompatible(MailerGenericBuilder.class.getMethod("withMailSendObserver", MailSendObserver.class, Executor.class)).isCompatible()).isFalse();
		assertThat(BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(new Class<?>[]{MailerRegularBuilder.class, MailerFromSessionBuilder.class}))
				.extracting(CliDeclaredOptionSpec::getName)
				.contains("--mailer:withMailSendTimeout", "--mailer:resetMailSendTimeout")
				.doesNotContain("--mailer:withMailSendObserver", "--mailer:getCompletion", "--mailer:requestCancellation");
	}

	@Test
	public void exactEmlBuilderExposesFileAndStringEnvelopeOptionsToCli() throws Exception {
		assertThat(methodIsCliCompatible(EmailStartingBuilder.class.getMethod("startingFromExactEml", byte[].class)).isCompatible()).isTrue();
		assertThat(methodIsCliCompatible(EmailStartingBuilder.class.getMethod("startingFromExactEml", InputStream.class)).isCompatible()).isFalse();
		assertThat(methodIsCliCompatible(EmailPopulatingBuilder.class.getMethod("withAttachment",
				String.class, byte[].class, String.class)).isCompatible()).isFalse();
		assertThat(methodIsCliCompatible(ExactEmailBuilder.class.getMethod("withEnvelopeRecipients", String[].class)).isCompatible()).isTrue();
		assertThat(methodIsCliCompatible(ExactEmailBuilder.class.getMethod("withEnvelopeRecipients", Collection.class)).isCompatible()).isFalse();

		final List<CliDeclaredOptionSpec> declaredOptions = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(
				new Class<?>[] {EmailStartingBuilder.class});
		assertThat(declaredOptions).extracting(CliDeclaredOptionSpec::getName)
				.contains("--email:startingFromExactEml", "--email:withEnvelopeRecipients", "--email:withEnvelopeSender",
						"--email:withEnvelopeDsnNotifyOptions", "--email:withEnvelopeDsnReturnOption");
	}

	@Test
	public void cliOptionalParametersAreMappedToOptionalArguments() throws Exception {
		Method from = EmailPopulatingBuilder.class.getMethod("from", String.class, String.class);
		assertThat(getArgumentsForCliOption(from)).extracting("required").containsExactly(false, true);

		Method withProxy = MailerGenericBuilder.class.getMethod("withProxy", String.class, Integer.class);
		assertThat(hasCliOptionalParameter(withProxy, 0)).isTrue();
		assertThat(hasCliOptionalParameter(withProxy, 1)).isTrue();

		Method withSmtpClientHostname = MailerGenericBuilder.class.getMethod("withSmtpClientHostname", String.class);
		assertThat(methodIsCliCompatible(withSmtpClientHostname).isCompatible()).isTrue();
		assertThat(hasCliOptionalParameter(withSmtpClientHostname, 0)).isTrue();
	}

	@Test
	public void stringVarargsCliFacadeIsMappedAndConvertedForCli() throws Exception {
		Method withRecipients = CliEmailRecipientBuilder.class.getMethod("withRecipients", String.class, boolean.class, Message.RecipientType.class, String[].class);
		assertThat(methodIsCliCompatible(withRecipients).isCompatible()).isTrue();
		assertThat(getArgumentsForCliOption(withRecipients)).extracting("helpLabel")
				.containsExactly("TEXT", "BOOL", "NAME", "TEXT");

		List<CliDeclaredOptionSpec> declaredOptions = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(
				new Class<?>[] { EmailStartingBuilder.class, CliEmailRecipientBuilder.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class });
		assertThat(declaredOptions).extracting(CliDeclaredOptionSpec::getName)
				.contains("--email:withRecipients");

		List<Object> fullValues = CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(Arrays.asList(
				"Team", "false", "TO", "alice@example.com;bob@example.com")), withRecipients);
		assertThat(fullValues.get(0)).isEqualTo("Team");
		assertThat(fullValues.get(1)).isEqualTo(false);
		assertThat(fullValues.get(2)).isEqualTo(Message.RecipientType.TO);
		assertThat((String[]) fullValues.get(3)).containsExactly("alice@example.com", "bob@example.com");

		List<Object> minimalValues = CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(Arrays.asList(
				"false", "alice@example.com,bob@example.com")), withRecipients);
		assertThat(minimalValues.get(0)).isNull();
		assertThat(minimalValues.get(1)).isEqualTo(false);
		assertThat(minimalValues.get(2)).isNull();
		assertThat((String[]) minimalValues.get(3)).containsExactly("alice@example.com", "bob@example.com");
	}

	@Test
	public void dedicatedRecipientOptionsRemainCliOnly() throws Exception {
		Method to = CliEmailRecipientBuilder.class.getMethod("to", String.class, String.class);
		assertThat(methodIsCliCompatible(to).isCompatible()).isTrue();
		assertThat(getArgumentsForCliOption(to)).extracting("helpLabel")
				.containsExactly("TEXT", "TEXT");
		assertThat(getArgumentsForCliOption(to)).extracting("required")
				.containsExactly(false, true);

		List<CliDeclaredOptionSpec> declaredOptions = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(
				new Class<?>[] { EmailStartingBuilder.class, CliEmailRecipientBuilder.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class });
		assertThat(declaredOptions).extracting(CliDeclaredOptionSpec::getName)
				.contains("--email:to", "--email:cc", "--email:bcc");

		assertThat(Arrays.stream(EmailPopulatingBuilder.class.getMethods()).map(Method::getName))
				.doesNotContain("to", "cc", "bcc");
		assertThatThrownBy(() -> EmailPopulatingBuilder.class.getMethod("withRecipients", String.class, boolean.class,
				Message.RecipientType.class, String[].class))
				.isInstanceOf(NoSuchMethodException.class);
	}

	@Test
	public void mailerFacadeSendApisAreNotGeneratedAsCliBuilderOption() throws Exception {
		for (Class<?> view : List.of(Mailer.Sync.class, Mailer.Async.class)) {
			for (Method operation : view.getMethods()) {
				assertThat(methodIsCliCompatible(operation).isCompatible()).isFalse();
				assertThat(methodIsCliCompatible(operation).getReason()).contains("@BuilderApiNode missing");
			}
		}
		Method openConnectionSend = Mailer.class.getMethod("withOpenConnection", OpenConnectionCallback.class);
		assertThat(methodIsCliCompatible(openConnectionSend).isCompatible()).isFalse();
		assertThat(methodIsCliCompatible(openConnectionSend).getReason()).contains("@BuilderApiNode missing");

		List<CliDeclaredOptionSpec> declaredOptions = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(
				new Class<?>[] { EmailStartingBuilder.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class });
		assertThat(declaredOptions).extracting(CliDeclaredOptionSpec::getName)
				.doesNotContain("--mailer:sendMailsInSimpleBatch", "--mailer:withOpenConnection", "--mailer:sendMail", "--mailer:sendMailAndGetReceipt",
						"--mailer:sync", "--mailer:async");
	}

	@Test
	public void generatedArgumentsFollowExplicitCliOptionality() {
		final List<CliDeclaredOptionSpec> options = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(
				new Class<?>[] {EmailStartingBuilder.class, CliEmailRecipientBuilder.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class});
		assertThat(options).isNotEmpty();
		for (final CliDeclaredOptionSpec option : options) {
			final Method method = option.getSourceMethod();
			assertThat(option.getPossibleOptionValues()).hasSize(method.getParameterCount());
			for (int parameterIndex = 0; parameterIndex < method.getParameterCount(); parameterIndex++) {
				assertThat(option.getPossibleOptionValues().get(parameterIndex).isRequired())
						.as("%s parameter %s", option.getName(), parameterIndex)
						.isEqualTo(!hasCliOptionalParameter(method, parameterIndex));
			}
		}
	}

	private static boolean hasCliOptionalParameter(Method method, int parameterIndex) {
		for (Annotation annotation : method.getParameterAnnotations()[parameterIndex]) {
			if (annotation.annotationType() == Cli.Optional.class) {
				return true;
			}
		}
		return false;
	}

}
