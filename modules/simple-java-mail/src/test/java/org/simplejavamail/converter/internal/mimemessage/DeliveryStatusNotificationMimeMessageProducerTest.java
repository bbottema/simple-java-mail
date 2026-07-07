package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static jakarta.mail.Message.RecipientType.TO;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;

public class DeliveryStatusNotificationMimeMessageProducerTest {

	@Test
	public void emailToMimeMessage_PrimesDeliveryStatusNotificationOnSmtpMessage() throws Exception {
		MimeMessage message = EmailConverter.emailToMimeMessage(EmailBuilder.startingBlank()
				.from("sender@example.com")
				.withRecipients(null, false, TO, "receiver@example.com")
				.withPlainText("Hello")
				.withDeliveryStatusNotification(HEADERS_ONLY, FAILURE, DELAY)
				.buildEmail());

		assertThat(message).isInstanceOf(ImmutableDelegatingSMTPMessage.class);
		SMTPMessage smtpMessage = (SMTPMessage) message;
		assertThat(smtpMessage.getNotifyOptions()).isEqualTo(SMTPMessage.NOTIFY_FAILURE | SMTPMessage.NOTIFY_DELAY);
		assertThat(smtpMessage.getReturnOption()).isEqualTo(SMTPMessage.RETURN_HDRS);
		assertThat(invokePackagePrivateSmtpMessageMethod(smtpMessage, "getDSNNotify")).isEqualTo("FAILURE,DELAY");
		assertThat(invokePackagePrivateSmtpMessageMethod(smtpMessage, "getDSNRet")).isEqualTo("HDRS");
	}

	private static Object invokePackagePrivateSmtpMessageMethod(SMTPMessage smtpMessage, String methodName) throws Exception {
		Method method = SMTPMessage.class.getDeclaredMethod(methodName);
		method.setAccessible(true);
		return method.invoke(smtpMessage);
	}
}
