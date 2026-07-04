package testutil;

import jakarta.mail.util.ByteArrayDataSource;
import lombok.SneakyThrows;
import org.assertj.core.util.Lists;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.CustomMailer;
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
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
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
				.to("C.Cane", "candycane@candyshop.org");

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
				/*5*/10,
				/*6*/1000,
				/*7*/randomUUID(),
				/*8*/0,
				/*9*/1,
				/*10*/5000,
				/*11*/10000,
				/*12*/ROUND_ROBIN,
				/*13*/false,
				/*14*/false,
				/*15*/null,
				/*16*/false,
				/*17*/ofNullable(hostsToTrust).orElse(Lists.emptyList()),
				/*18*/trustAllSSLHost,
				/*19*/verifyServerIdentity,
				/*20*/newSingleThreadExecutor(),
				/*21*/false,
				/*22*/null);
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	public static OperationalConfig createDummyOperationalConfig(
			/*0*/final boolean async,
			/*1*/@Nullable final Properties properties,
			/*2*/final int sessionTimeout,
			/*3*/@Nullable final String localBindAddress,
			/*4*/@Nullable final Integer localBindPort,
			/*5*/final int threadPoolSize,
			/*6*/final int threadPoolKeepAliveTime,
			/*7*/@NotNull final UUID clusterKey,
			/*8*/final int connectionPoolCoreSize,
			/*9*/final int connectionPoolMaxSize,
			/*10*/final int connectionPoolClaimTimeoutMillis,
			/*11*/final int connectionPoolExpireAfterMillis,
			/*12*/@NotNull final LoadBalancingStrategy connectionPoolLoadBalancingStrategy,
			/*13*/final boolean transportModeLoggingOnly,
			/*14*/final boolean debugLogging,
			/*15*/@Nullable final PrintStream debugPrinter,
			/*16*/final boolean disableAllClientValidation,
			/*17*/@NotNull final List<String> sslHostsToTrust,
			/*18*/final boolean trustAllSSLHost,
			/*19*/final boolean verifyingServerIdentity,
			/*20*/@NotNull final ExecutorService executorService,
			/*21*/final boolean isExecutorServiceUserProvided,
			/*22*/@Nullable final CustomMailer customMailer) {
		try {
			Constructor<?> constructor = Class.forName("org.simplejavamail.mailer.internal.OperationalConfigImpl").getDeclaredConstructors()[0];
			constructor.setAccessible(true);
			return (OperationalConfig) constructor.newInstance(
					/*0*/async,
					/*1*/properties,
					/*2*/sessionTimeout,
					/*3*/localBindAddress,
					/*4*/localBindPort,
					/*5*/threadPoolSize,
					/*6*/threadPoolKeepAliveTime,
					/*7*/clusterKey,
					/*8*/connectionPoolCoreSize,
					/*9*/connectionPoolMaxSize,
					/*10*/connectionPoolClaimTimeoutMillis,
					/*11*/connectionPoolExpireAfterMillis,
					/*12*/connectionPoolLoadBalancingStrategy,
					/*13*/transportModeLoggingOnly,
					/*14*/debugLogging,
					/*15*/debugPrinter,
					/*16*/disableAllClientValidation,
					/*17*/sslHostsToTrust,
					/*18*/trustAllSSLHost,
					/*19*/verifyingServerIdentity,
					/*20*/executorService,
					/*21*/isExecutorServiceUserProvided,
					/*22*/customMailer);
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}
}
