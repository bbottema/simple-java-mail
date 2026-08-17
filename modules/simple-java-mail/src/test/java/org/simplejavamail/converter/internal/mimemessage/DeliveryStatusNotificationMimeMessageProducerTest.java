package org.simplejavamail.converter.internal.mimemessage;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;
import testutil.EmailHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static jakarta.mail.Message.RecipientType.TO;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;

public class DeliveryStatusNotificationMimeMessageProducerTest {

	@Test
	public void preparedMailCarriesDeliveryStatusNotificationOutsideMimeMessage() throws Exception {
		final Mailer mailer = MailerBuilder.withSMTPServer("localhost", 25).buildMailer();
		final PreparedMail preparedMail = SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(
				mailer.getSession(), EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(EmailHelper.parsedRecipients(null, false, TO, "receiver@example.com"))
				.withPlainText("Hello")
				.withBounceTo("bounce@example.com")
				.withDeliveryStatusNotification(HEADERS_ONLY, FAILURE, DELAY)
				.buildEmailCompletedWithDefaultsAndOverrides());

		assertThat(preparedMail.getMimeMessage()).isInstanceOf(FinalizedMimeMessage.class);
		assertThat(preparedMail.getDeliveryEnvelope().getEnvelopeFrom()).isEqualTo("bounce@example.com");
		assertThat(preparedMail.getDeliveryEnvelope().getDeliveryStatusNotification().getNotifyOptions())
				.containsExactlyInAnyOrder(FAILURE, DELAY);
		assertThat(preparedMail.getDeliveryEnvelope().getDeliveryStatusNotification().getReturnOption())
				.isEqualTo(HEADERS_ONLY);
	}
}
