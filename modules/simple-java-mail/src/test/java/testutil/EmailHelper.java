package testutil;

import java.time.Duration;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.SneakyThrows;
import org.assertj.core.util.Lists;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import org.simplejavamail.recipient.internal.RecipientsBuilderImpl;

import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Calendar.SEPTEMBER;
import static java.util.Optional.ofNullable;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_CONNECTION_CONFLICT_POLICY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_RATE_LIMIT_BURSTS_ALLOWED;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_TELEMETRY_ENABLED;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES;
import static org.simplejavamail.api.mailer.config.LoadBalancingStrategy.ROUND_ROBIN;
import static org.simplejavamail.converter.EmailConverter.emlToEmailBuilder;
import static org.simplejavamail.converter.EmailConverter.outlookMsgToEmailBuilderWithOutlookData;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

public class EmailHelper {

	public static final Date CUSTOM_SENT_DATE = new GregorianCalendar(2011, SEPTEMBER, 15, 12, 5, 43).getTime();

	@NotNull
	public static Collection<Recipient> parsedRecipients(@Nullable String name, boolean fixedName,
			@Nullable jakarta.mail.Message.RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach) {
		return new RecipientsBuilderImpl()
				.withRecipients(name, fixedName, recipientType, oneOrMoreAddressesEach)
				.buildRecipients();
	}

	public static EmailPopulatingBuilder createDummyEmailBuilder(boolean includeSubjectAndBody, boolean skipReplyToAndBounceTo, boolean includeCustomHeaders, boolean useSmimeDetailsImplFromSmimeModule, boolean useDynamicImageEmbedding, final boolean includeCalendarText) {
		return createDummyEmailBuilder(null, includeSubjectAndBody, skipReplyToAndBounceTo, includeCustomHeaders, useSmimeDetailsImplFromSmimeModule, false, useDynamicImageEmbedding, includeCalendarText);
	}

	@SneakyThrows
	public static EmailPopulatingBuilder createDummyEmailBuilder(@Nullable String id, boolean includeSubjectAndBody, boolean skipReplyToAndBounceTo, boolean includeCustomHeaders,
																 boolean useSmimeDetailsImplFromSmimeModule, final boolean fixSentDate, final boolean useDynamicImageEmbedding, final boolean includeCalendarText) {
		EmailPopulatingBuilder builder = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
				.fixingMessageId(id)
				.from("lollypop", "lol.pop@somemail.com")
				// don't forget to add your own address here ->
				.withRecipients(parsedRecipients("C.Cane", true, TO, "candycane@candyshop.org"));

		if (!skipReplyToAndBounceTo) {
			// normally not needed, but for the test it is because the MimeMessage will
			// have it added automatically as well, so the parsed Email will also have it then
			builder = builder
					.withReplyTo("lollypop-reply", "lol.pop.reply@somemail.com")
					.withBounceTo("lollypop-bounce", "lol.pop.bounce@somemail.com");
		}
		if (includeSubjectAndBody) {
			builder = builder
					.withSubject("hey")
					.withPlainText("We should meet up!")
					.withHTMLText("<b>We should meet up!</b><img src='cid:thumbsup'><img src='cid:fixedNameWithoutFileExtensionForNamedEmbeddedImage'>");
		}
		if (useDynamicImageEmbedding) {
			builder = builder
					.appendHTMLText("<img src=\"/test-dynamicembedded-image/br2049.jpg\"/>");
		}

		if (includeCustomHeaders) {
			builder = builder
					.withHeader("dummyHeader", "dummyHeaderValue")
					.withHeader("dummyHeader", "dummyHeaderValueSecond")
					.withHeader("anotherDummyHeader", "anotherDummyHeaderValue")
					.withHeader("governanceOverrideTest2", "initial value") // overriden by emailGovernance in MailerLiveTest
					.withDispositionNotificationTo("simple@address.com")
					.withReturnReceiptTo("Complex Email", "simple@address.com");
		}

		if (includeCalendarText) {
			builder = builder.withCalendarText(CalendarMethod.CANCEL, "Sorry, can't make it");
		}

		if (fixSentDate) {
			builder = builder.fixingSentDate(CUSTOM_SENT_DATE);
		}

		final String base64StringOfThumbsupImage = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";

		// add two text files in different ways and a black thumbs up embedded image ->
		ByteArrayDataSource namedAttachment = new ByteArrayDataSource("Black Tie Optional", "text/plain");
		namedAttachment.setName("dresscode-ignored-because-of-override.txt");
		ByteArrayDataSource namedEmbeddedImage = new ByteArrayDataSource(Base64.getDecoder().decode(base64StringOfThumbsupImage), "image/png");
		namedEmbeddedImage.setName("thumbsupNamed-ignored-because-of-override.png");

		InternalEmailPopulatingBuilder internalBuilder = ((InternalEmailPopulatingBuilder) builder
				.withAttachment("dresscode.txt", namedAttachment)
				.withAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain")
				.withEmbeddedImage("thumbsup", Base64.getDecoder().decode(base64StringOfThumbsupImage), "image/png")
				// attachment name tests when producing MimeMessage ->
				.withAttachment("fixedNameWithoutFileExtensionForNamedAttachment", namedAttachment) // this should be overridden by appending file extension
				.withEmbeddedImage("fixedNameWithoutFileExtensionForNamedEmbeddedImage", namedEmbeddedImage)) // this should not be overridden
				.withDecryptedAttachments(builder.getAttachments());

		if (useSmimeDetailsImplFromSmimeModule) {
			internalBuilder.withOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder().build());
		}

		return internalBuilder;
	}

	public static EmailPopulatingBuilder readOutlookMessage(final String filePath) {
		InputStream resourceAsStream = EmailHelper.class.getClassLoader().getResourceAsStream(filePath);
		return outlookMsgToEmailBuilderWithOutlookData(checkNonEmptyArgument(resourceAsStream, "resourceAsStream")).getEmailBuilder();
	}

	public static EmailPopulatingBuilder readEmlMessage(final String filePath) {
		InputStream resourceAsStream = EmailHelper.class.getClassLoader().getResourceAsStream(filePath);
		return emlToEmailBuilder(checkNonEmptyArgument(resourceAsStream, "resourceAsStream"));
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	public static OperationalConfig createDummyOperationalConfig(@Nullable List<String> hostsToTrust, boolean trustAllSSLHost, boolean verifyServerIdentity) {
		return createDummyOperationalConfig(
				/*0*/new Properties(),
				/*1*/0,
				/*2*/null,
				/*3*/null,
				/*4*/null,
				/*5*/10,
				/*6*/1000,
				/*7*/randomUUID(),
				/*8*/0,
				/*9*/1,
				/*10*/5000,
				/*11*/10000,
				/*12*/ROUND_ROBIN,
				/*13*/Collections.emptyMap(),
				/*14*/false,
				/*15*/false,
				/*16*/null,
				/*17*/false,
				/*18*/ofNullable(hostsToTrust).orElse(Lists.emptyList()),
				/*19*/trustAllSSLHost,
				/*20*/verifyServerIdentity,
				/*21*/newSingleThreadExecutor(),
				/*22*/false,
				/*23*/null,
				/*24*/null);
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	public static OperationalConfig createDummyOperationalConfig(
			/*0*/@Nullable final Properties properties,
			/*1*/final int sessionTimeout,
			/*2*/@Nullable final String localBindAddress,
			/*3*/@Nullable final Integer localBindPort,
			/*4*/@Nullable final String smtpClientHostname,
			/*5*/final int threadPoolSize,
			/*6*/final int threadPoolKeepAliveTime,
			/*7*/@NotNull final UUID clusterKey,
			/*8*/final int connectionPoolCoreSize,
			/*9*/final int connectionPoolMaxSize,
			/*10*/final int connectionPoolClaimTimeoutMillis,
			/*11*/final int connectionPoolExpireAfterMillis,
			/*12*/@NotNull final LoadBalancingStrategy connectionPoolLoadBalancingStrategy,
			/*13*/@NotNull final Map<UUID, ConnectionPoolClusterConfig> connectionPoolClusterConfigs,
			/*14*/final boolean transportModeLoggingOnly,
			/*15*/final boolean debugLogging,
			/*16*/@Nullable final PrintStream debugPrinter,
			/*17*/final boolean clientValidationWarningsOnly,
			/*18*/@NotNull final List<String> sslHostsToTrust,
			/*19*/final boolean trustAllSSLHost,
			/*20*/final boolean verifyingServerIdentity,
			/*21*/@NotNull final ExecutorService executorService,
			/*22*/final boolean isExecutorServiceUserProvided,
			/*23*/@Nullable final CustomMailer customMailer,
			/*24*/@Nullable final OAuth2AccessTokenProvider oauth2AccessTokenProvider) {
		try {
			Constructor<?> constructor = Class.forName("org.simplejavamail.mailer.internal.OperationalConfigImpl").getDeclaredConstructors()[0];
			constructor.setAccessible(true);
			final SessionDebugConfig debugConfig = debugPrinter == null
					? SessionDebugConfig.builder().withEnabled(debugLogging).withOutput(SessionDebugOutput.STDOUT).build()
					: SessionDebugConfig.builder().withEnabled(debugLogging).withPrinter(debugPrinter).build();
			final Duration socketTimeout = Duration.ofMillis(sessionTimeout);
			return (OperationalConfig) constructor.newInstance(
					/*0*/properties,
					/*1*/DEFAULT_CONNECTION_CONFLICT_POLICY,
					/*2*/socketTimeout,
					/*3*/socketTimeout,
					/*4*/socketTimeout,
					/*5*/localBindAddress,
					/*6*/localBindPort,
					/*7*/smtpClientHostname,
					/*8*/false,
					/*9*/MailSendTelemetryConfig.builder().withEnabled(DEFAULT_MAIL_SEND_TELEMETRY_ENABLED)
							.withSmtpEndpointAttributes(DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES).build(),
					/*10*/threadPoolSize,
					/*11*/Duration.ofMillis(threadPoolKeepAliveTime),
					/*12*/clusterKey,
					/*13*/connectionPoolCoreSize,
					/*14*/connectionPoolMaxSize,
					/*15*/Duration.ofMillis(connectionPoolClaimTimeoutMillis),
					/*16*/Duration.ofMillis(connectionPoolExpireAfterMillis),
					/*17*/null,
					/*18*/connectionPoolLoadBalancingStrategy,
					/*19*/connectionPoolClusterConfigs,
					/*20*/transportModeLoggingOnly,
					/*21*/debugConfig,
					/*22*/clientValidationWarningsOnly,
					/*23*/sslHostsToTrust,
					/*24*/trustAllSSLHost,
					/*25*/verifyingServerIdentity,
					/*26*/executorService,
					/*27*/isExecutorServiceUserProvided,
					/*28*/customMailer,
					/*29*/oauth2AccessTokenProvider,
					/*30*/new AsyncQueueConfig(-1, AsyncQueueOverflowPolicy.REJECT, Duration.ofMillis(1000)),
					/*31*/null,
					/*32*/null,
					/*33*/null,
					/*34*/null,
					/*35*/DEFAULT_RATE_LIMIT_BURSTS_ALLOWED,
					/*36*/null,
					/*37*/MailSendRetryPolicy.builder().build());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}
}
