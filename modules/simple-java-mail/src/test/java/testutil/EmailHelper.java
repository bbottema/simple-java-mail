package testutil;

import jakarta.mail.util.ByteArrayDataSource;
import lombok.SneakyThrows;
import org.assertj.core.util.Lists;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static java.util.Calendar.SEPTEMBER;
import static java.util.Optional.ofNullable;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.simplejavamail.api.mailer.config.LoadBalancingStrategy.ROUND_ROBIN;
import static org.simplejavamail.converter.EmailConverter.emlToEmailBuilder;
import static org.simplejavamail.converter.EmailConverter.outlookMsgToEmailBuilderWithOutlookData;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static jakarta.mail.Message.RecipientType.TO;

public class EmailHelper {

	public static final Date CUSTOM_SENT_DATE = new GregorianCalendar(2011, SEPTEMBER, 15, 12, 5, 43).getTime();

	public static EmailPopulatingBuilder createDummyEmailBuilder(boolean includeSubjectAndBody, boolean skipReplyToAndBounceTo, boolean includeCustomHeaders, boolean useSmimeDetailsImplFromSmimeModule, boolean useDynamicImageEmbedding, final boolean includeCalendarText) {
		return createDummyEmailBuilder(null, includeSubjectAndBody, skipReplyToAndBounceTo, includeCustomHeaders, useSmimeDetailsImplFromSmimeModule, false, useDynamicImageEmbedding, includeCalendarText);
	}

	@SneakyThrows
	public static EmailPopulatingBuilder createDummyEmailBuilder(@Nullable String id, boolean includeSubjectAndBody, boolean skipReplyToAndBounceTo, boolean includeCustomHeaders,
																 boolean useSmimeDetailsImplFromSmimeModule, final boolean fixSentDate, final boolean useDynamicImageEmbedding, final boolean includeCalendarText) {
		EmailPopulatingBuilder builder = EmailBuilder.startingBlank()
				.fixingMessageId(id)
				.from("lollypop", "lol.pop@somemail.com")
				// don't forget to add your own address here ->
				.withRecipients("C.Cane", true, TO, "candycane@candyshop.org");

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
					.appendTextHTML("<img src=\"/test-dynamicembedded-image/br2049.jpg\"/>");
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
				/*0*/false,
				/*1*/new Properties(),
				/*2*/0,
				/*3*/null,
				/*4*/null,
				/*5*/null,
				/*6*/10,
				/*7*/1000,
				/*8*/randomUUID(),
				/*9*/0,
				/*10*/1,
				/*11*/5000,
				/*12*/10000,
				/*13*/ROUND_ROBIN,
				/*14*/Collections.emptyMap(),
				/*15*/false,
				/*16*/false,
				/*17*/null,
				/*18*/false,
				/*19*/ofNullable(hostsToTrust).orElse(Lists.emptyList()),
				/*20*/trustAllSSLHost,
				/*21*/verifyServerIdentity,
				/*22*/newSingleThreadExecutor(),
				/*23*/false,
				/*24*/null);
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	public static OperationalConfig createDummyOperationalConfig(
			/*0*/final boolean async,
			/*1*/@Nullable final Properties properties,
			/*2*/final int sessionTimeout,
			/*3*/@Nullable final String localBindAddress,
			/*4*/@Nullable final Integer localBindPort,
			/*5*/@Nullable final String smtpClientHostname,
			/*6*/final int threadPoolSize,
			/*7*/final int threadPoolKeepAliveTime,
			/*8*/@NotNull final UUID clusterKey,
			/*9*/final int connectionPoolCoreSize,
			/*10*/final int connectionPoolMaxSize,
			/*11*/final int connectionPoolClaimTimeoutMillis,
			/*12*/final int connectionPoolExpireAfterMillis,
			/*13*/@NotNull final LoadBalancingStrategy connectionPoolLoadBalancingStrategy,
			/*14*/@NotNull final Map<UUID, ConnectionPoolClusterConfig> connectionPoolClusterConfigs,
			/*15*/final boolean transportModeLoggingOnly,
			/*16*/final boolean debugLogging,
			/*17*/@Nullable final PrintStream debugPrinter,
			/*18*/final boolean disableAllClientValidation,
			/*19*/@NotNull final List<String> sslHostsToTrust,
			/*20*/final boolean trustAllSSLHost,
			/*21*/final boolean verifyingServerIdentity,
			/*22*/@NotNull final ExecutorService executorService,
			/*23*/final boolean isExecutorServiceUserProvided,
			/*24*/@Nullable final CustomMailer customMailer) {
		try {
			Constructor<?> constructor = Class.forName("org.simplejavamail.mailer.internal.OperationalConfigImpl").getDeclaredConstructors()[0];
			constructor.setAccessible(true);
			return (OperationalConfig) constructor.newInstance(
					/*0*/async,
					/*1*/properties,
					/*2*/sessionTimeout,
					/*3*/localBindAddress,
					/*4*/localBindPort,
					/*5*/smtpClientHostname,
					/*6*/threadPoolSize,
					/*7*/threadPoolKeepAliveTime,
					/*8*/clusterKey,
					/*9*/connectionPoolCoreSize,
					/*10*/connectionPoolMaxSize,
					/*11*/connectionPoolClaimTimeoutMillis,
					/*12*/connectionPoolExpireAfterMillis,
					/*13*/connectionPoolLoadBalancingStrategy,
					/*14*/connectionPoolClusterConfigs,
					/*15*/transportModeLoggingOnly,
					/*16*/debugLogging,
					/*17*/debugPrinter,
					/*18*/disableAllClientValidation,
					/*19*/sslHostsToTrust,
					/*20*/trustAllSSLHost,
					/*21*/verifyingServerIdentity,
					/*22*/executorService,
					/*23*/isExecutorServiceUserProvided,
					/*24*/customMailer);
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}
}
