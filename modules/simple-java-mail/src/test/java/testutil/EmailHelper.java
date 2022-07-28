package testutil;

import jakarta.mail.util.ByteArrayDataSource;
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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static jakarta.xml.bind.DatatypeConverter.parseBase64Binary;
import static java.util.Calendar.SEPTEMBER;
import static java.util.Optional.ofNullable;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.simplejavamail.api.mailer.config.LoadBalancingStrategy.ROUND_ROBIN;
import static org.simplejavamail.converter.EmailConverter.emlToEmailBuilder;
import static org.simplejavamail.converter.EmailConverter.outlookMsgToEmailBuilder;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

public class EmailHelper {

	public static final Date CUSTOM_SENT_DATE = new GregorianCalendar(2011, SEPTEMBER, 15, 12, 5, 43).getTime();

	public static EmailPopulatingBuilder createDummyEmailBuilder(boolean includeSubjectAndBody, boolean onlyBasicFields, boolean includeCustomHeaders, boolean useSmimeDetailsImplFromSmimeModule, boolean useDynamicImageEmbedding, final boolean includeCalendarText)
			throws IOException {
		return createDummyEmailBuilder(null, includeSubjectAndBody, onlyBasicFields, includeCustomHeaders, useSmimeDetailsImplFromSmimeModule, false, useDynamicImageEmbedding, includeCalendarText);
	}

	public static EmailPopulatingBuilder createDummyEmailBuilder(@Nullable String id, boolean includeSubjectAndBody, boolean onlyBasicFields, boolean includeCustomHeaders,
			boolean useSmimeDetailsImplFromSmimeModule, final boolean fixSentDate, final boolean useDynamicImageEmbedding, final boolean includeCalendarText)
			throws IOException {
		EmailPopulatingBuilder builder = EmailBuilder.startingBlank()
				.fixingMessageId(id)
				.from("lollypop", "lol.pop@somemail.com")
				// don't forget to add your own address here ->
				.to("C.Cane", "candycane@candyshop.org");

		if (!onlyBasicFields) {
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
					.withHTMLText("<b>We should meet up!</b><img src='cid:thumbsup'>");
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
					.withDispositionNotificationTo("simple@address.com")
					.withReturnReceiptTo("Complex Email", "simple@address.com");
		}

		if (includeCalendarText) {
			builder = builder.withCalendarText(CalendarMethod.CANCEL, "Sorry, can't make it");
		}

		if (fixSentDate) {
			builder = builder.fixingSentDate(CUSTOM_SENT_DATE);
		}

		// add two text files in different ways and a black thumbs up embedded image ->
		ByteArrayDataSource namedAttachment = new ByteArrayDataSource("Black Tie Optional", "text/plain");
		namedAttachment.setName("dresscode-ignored-because-of-override.txt");
		String base64String = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABeElEQVRYw2NgoAAYGxu3GxkZ7TY1NZVloDcAWq4MxH+B+D8Qv3FwcOCgtwM6oJaDMTAUXOhmuYqKCjvQ0pdoDrCnmwNMTEwakC0H4u8GBgYC9Ap6DSD+iewAoIPm0ctyLqBlp9F8/x+YE4zpYT8T0LL16JYD8U26+B7oyz4sloPwenpYno3DchCeROsUbwa05A8eB3wB4kqgIxOAuArIng7EW4H4EhC/B+JXQLwDaI4ryZaDSjeg5mt4LCcFXyIn1fdSyXJQVt1OtMWGhoai0OD8T0W8GohZifE1PxD/o7LlsPLiFNAKRrwOABWptLAcqc6QGDAHQEOAYaAc8BNotsJAOgAUAosG1AFA/AtUoY3YEFhKMAvS2AE7iC1+WaG1H6gY3gzE36hUFJ8mqzbU1dUVBBqQBzTgIDQRkWo5qCZdpaenJ0Zx1aytrc0DDB0foIG1oAYKqC0IZK8D4n1AfA6IzwPxXpCFoGoZVEUDaRGGUTAKRgEeAAA2eGJC+ETCiAAAAABJRU5ErkJggg==";

		InternalEmailPopulatingBuilder internalBuilder = ((InternalEmailPopulatingBuilder) builder
				.withAttachment("dresscode.txt", namedAttachment)
				.withAttachment("location.txt", "On the moon!".getBytes(Charset.defaultCharset()), "text/plain")
				.withEmbeddedImage("thumbsup", parseBase64Binary(base64String), "image/png"))
				.withDecryptedAttachments(builder.getAttachments());

		if (useSmimeDetailsImplFromSmimeModule) {
			internalBuilder.withOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder().build());
		}

		return internalBuilder;
	}

	public static EmailPopulatingBuilder readOutlookMessage(final String filePath) {
		InputStream resourceAsStream = EmailHelper.class.getClassLoader().getResourceAsStream(filePath);
		return outlookMsgToEmailBuilder(checkNonEmptyArgument(resourceAsStream, "resourceAsStream")).getEmailBuilder();
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
				/*3*/10,
				/*4*/1000,
				/*5*/randomUUID(),
				/*6*/0,
				/*7*/1,
				/*8*/5000,
				/*9*/10000,
				/*10*/ROUND_ROBIN,
				/*11*/false,
				/*12*/false,
				/*13*/false,
				/*14*/ofNullable(hostsToTrust).orElse(Lists.emptyList()),
				/*15*/trustAllSSLHost,
				/*16*/verifyServerIdentity,
				/*17*/newSingleThreadExecutor(),
				/*18*/false,
				/*19*/null);
	}

	@NotNull
	@SuppressWarnings("SameParameterValue")
	public static OperationalConfig createDummyOperationalConfig(
			/*0*/final boolean async,
			/*1*/@Nullable final Properties properties,
			/*2*/final int sessionTimeout,
			/*3*/final int threadPoolSize,
			/*4*/final int threadPoolKeepAliveTime,
			/*5*/@NotNull final UUID clusterKey,
			/*6*/final int connectionPoolCoreSize,
			/*7*/final int connectionPoolMaxSize,
			/*8*/final int connectionPoolClaimTimeoutMillis,
			/*9*/final int connectionPoolExpireAfterMillis,
			/*10*/@NotNull final LoadBalancingStrategy connectionPoolLoadBalancingStrategy,
			/*11*/final boolean transportModeLoggingOnly,
			/*12*/final boolean debugLogging,
			/*13*/final boolean disableAllClientValidation,
			/*14*/@NotNull final List<String> sslHostsToTrust,
			/*15*/final boolean trustAllSSLHost,
			/*16*/final boolean verifyingServerIdentity,
			/*17*/@NotNull final ExecutorService executorService,
			/*18*/final boolean isExecutorServiceUserProvided,
			/*19*/@Nullable final CustomMailer customMailer) {
		try {
			Constructor<?> constructor = Class.forName("org.simplejavamail.mailer.internal.OperationalConfigImpl").getDeclaredConstructors()[0];
			constructor.setAccessible(true);
			return (OperationalConfig) constructor.newInstance(
					/*0*/async,
					/*1*/properties,
					/*2*/sessionTimeout,
					/*3*/threadPoolSize,
					/*4*/threadPoolKeepAliveTime,
					/*5*/clusterKey,
					/*6*/connectionPoolCoreSize,
					/*7*/connectionPoolMaxSize,
					/*8*/connectionPoolClaimTimeoutMillis,
					/*9*/connectionPoolExpireAfterMillis,
					/*10*/connectionPoolLoadBalancingStrategy,
					/*11*/transportModeLoggingOnly,
					/*12*/debugLogging,
					/*13*/disableAllClientValidation,
					/*14*/sslHostsToTrust,
					/*15*/trustAllSSLHost,
					/*16*/verifyingServerIdentity,
					/*17*/executorService,
					/*18*/isExecutorServiceUserProvided,
					/*19*/customMailer);
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}
}
