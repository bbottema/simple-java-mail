package org.simplejavamail.converter.internal.mimemessage;

import com.sun.mail.smtp.SMTPMessage;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.api.iterable.Extractor;
import org.junit.Test;
import org.simplejavamail.api.email.Email;

import javax.activation.DataHandler;
import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Header;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.HeaderTerm;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.Properties;

import static java.util.Collections.list;
import static javax.mail.Flags.Flag.ANSWERED;
import static javax.mail.Message.RecipientType.BCC;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.FieldsOrPropertiesExtractor.extract;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.simplejavamail.converter.EmailConverter.emailToMimeMessage;
import static testutil.EmailHelper.createDummyEmailBuilder;

public class ImmutableDelegatingSMTPMessageTest {

	private static final HeaderExtractor HEADER_EXTRACTOR = new HeaderExtractor();

	@Test
	public void testSMTPMessageMethodsShouldBeDelegated() {
		SMTPMessage mockSmtpMessage = mock(SMTPMessage.class);
		ImmutableDelegatingSMTPMessage subject = new ImmutableDelegatingSMTPMessage(mockSmtpMessage, "envelop@from.com");

		subject.getAllow8bitMIME();
		verify(mockSmtpMessage).getAllow8bitMIME();
		subject.getSendPartial();
		verify(mockSmtpMessage).getSendPartial();
		subject.getSubmitter();
		verify(mockSmtpMessage).getSubmitter();
		subject.getMailExtension();
		verify(mockSmtpMessage).getMailExtension();
		subject.getNotifyOptions();
		verify(mockSmtpMessage).getNotifyOptions();
		subject.getReturnOption();
		verify(mockSmtpMessage).getReturnOption();
	}

	@Test
	public void testSMTPMessageMethodsShouldReturnFalseInAbsenceOfAProperDelegate() throws IOException {
		Email email = createDummyEmailBuilder("<id>", true, false, true, true, true).buildEmail();
		ImmutableDelegatingSMTPMessage subject = new ImmutableDelegatingSMTPMessage(emailToMimeMessage(email), "envelop@from.com");

		assertThat(subject.getAllow8bitMIME()).isFalse();
		assertThat(subject.getSendPartial()).isFalse();
		assertThat(subject.getSubmitter()).isNull();
		assertThat(subject.getMailExtension()).isNull();
		assertThat(subject.getNotifyOptions()).isZero();
		assertThat(subject.getReturnOption()).isZero();
	}

	@Test
	public void testGettersResultInEqualValues() throws MessagingException, IOException {
		Email email = createDummyEmailBuilder("<id>", true, false, true, true, true).clearBounceTo().buildEmail();
		MimeMessage message = emailToMimeMessage(email);
		message.setSender(new InternetAddress("a@b.com", "abcom"));

		ImmutableDelegatingSMTPMessage subject = new ImmutableDelegatingSMTPMessage(message, "envelop@from.com");
		assertThat(subject.getDelegate()).isSameAs(message);

		assertThat(subject.getEnvelopeFrom()).isEqualTo("envelop@from.com");
		
		assertThat(subject.getFrom()).isEqualTo(message.getFrom());
		assertThat(subject.getSender()).isEqualTo(message.getSender());
		assertThat(subject.getAllRecipients()).isEqualTo(message.getAllRecipients());
		assertThat(subject.getReplyTo()).isEqualTo(message.getReplyTo());
		assertThat(subject.getSubject()).isEqualTo(message.getSubject());
		assertThat(subject.getSentDate()).isEqualTo(message.getSentDate());
		assertThat(subject.getReceivedDate()).isEqualTo(message.getReceivedDate());
		assertThat(subject.getSize()).isEqualTo(message.getSize());
		assertThat(subject.getLineCount()).isEqualTo(message.getLineCount());
		assertThat(subject.getContentType()).isEqualTo(message.getContentType());
		assertThat(subject.getDisposition()).isEqualTo(message.getDisposition());
		assertThat(subject.getEncoding()).isEqualTo(message.getEncoding());
		assertThat(subject.getContentID()).isEqualTo(message.getContentID());
		assertThat(subject.getContentMD5()).isEqualTo(message.getContentMD5());
		assertThat(subject.getDescription()).isEqualTo(message.getDescription());
		assertThat(subject.getContentLanguage()).isEqualTo(message.getContentLanguage());
		assertThat(subject.getMessageID()).isEqualTo(message.getMessageID());
		assertThat(subject.getFileName()).isEqualTo(message.getFileName());
		assertThat(subject.getDataHandler()).isEqualTo(message.getDataHandler());
		assertThat(subject.getContent()).isEqualTo(message.getContent());
		assertThat(subject.getFlags()).isEqualTo(message.getFlags());
		assertThat(subject.getSession()).isEqualTo(message.getSession());
		assertThat(subject.getMessageNumber()).isEqualTo(message.getMessageNumber());
		assertThat(subject.getFolder()).isEqualTo(message.getFolder());
		assertThat(subject.isExpunged()).isEqualTo(message.isExpunged());

		assertThat(subject.getRecipients(TO)).isEqualTo(message.getRecipients(TO));
		assertThat(subject.getRecipients(CC)).isEqualTo(message.getRecipients(CC));
		assertThat(subject.getRecipients(BCC)).isEqualTo(message.getRecipients(BCC));
		assertThat(subject.getHeader("header")).isEqualTo(message.getHeader("header"));
		assertThat(subject.getHeader("header", ",")).isEqualTo(message.getHeader("header", ","));
		assertThat(list(subject.getAllHeaders())).extracting(HEADER_EXTRACTOR).isEqualTo(extract(list(message.getAllHeaders()), HEADER_EXTRACTOR));
		assertThat(list(subject.getAllHeaderLines())).isEqualTo(list(message.getAllHeaderLines()));
		assertThat(list(subject.getMatchingHeaders(new String[]{"dummyHeader", "header2"}))).extracting(HEADER_EXTRACTOR)
				.isEqualTo(extract(list(message.getMatchingHeaders(new String[]{"dummyHeader", "header2"})), HEADER_EXTRACTOR));
		assertThat(list(subject.getNonMatchingHeaders(new String[]{"dummyHeader", "header2"}))).extracting(HEADER_EXTRACTOR)
				.isEqualTo(extract(list(message.getNonMatchingHeaders(new String[]{"dummyHeader", "header2"})), HEADER_EXTRACTOR));
		assertThat(list(subject.getMatchingHeaderLines(new String[]{"dummyHeader", "header2"}))).isEqualTo(list(message.getMatchingHeaderLines(new String[]{"dummyHeader", "header2"})));
		assertThat(list(subject.getNonMatchingHeaderLines(new String[]{"dummyHeader", "header2"}))).isEqualTo(list(message.getNonMatchingHeaderLines(new String[]{"dummyHeader", "header2"})));
	}
	
	@Test
	public void testIrrelevantGettersAndAllowedMutatorsAreDelegatedProperly() throws MessagingException, IOException {
		MimeMessage mockMmessage = mock(MimeMessage.class);
		ImmutableDelegatingSMTPMessage subject = new ImmutableDelegatingSMTPMessage(mockMmessage, "envelop@from.com");

		final ByteArrayInputStream dummyIs = new ByteArrayInputStream(new byte[] {});
		when(mockMmessage.getRawInputStream()).thenReturn(dummyIs);
		when(mockMmessage.getInputStream()).thenReturn(dummyIs);

		assertThat(subject.getRawInputStream()).isSameAs(dummyIs);
		verify(mockMmessage).getRawInputStream();

		assertThat(subject.getInputStream()).isSameAs(dummyIs);
		verify(mockMmessage).getInputStream();

		assertThat(subject.isMimeType("moo")).isFalse();
		verify(mockMmessage).isMimeType("moo");

		final Message dummyMessage = mock(Message.class);
		when(mockMmessage.reply(anyBoolean())).thenReturn(dummyMessage);
		assertThat(subject.reply(true)).isSameAs(dummyMessage);
		verify(mockMmessage).reply(true);

		when(mockMmessage.reply(anyBoolean(), anyBoolean())).thenReturn(dummyMessage);
		assertThat(subject.reply(false, true)).isSameAs(dummyMessage);
		verify(mockMmessage).reply(false, true);

		assertThat(subject.isSet(ANSWERED)).isFalse();
		verify(mockMmessage).isSet(ANSWERED);

		HeaderTerm term = new HeaderTerm("name", "pattern");
		assertThat(subject.match(term)).isFalse();
		verify(mockMmessage).match(term);

		OutputStream bos = new ByteArrayOutputStream();
		subject.writeTo(bos);
		verify(mockMmessage).writeTo(bos);

		subject.writeTo(bos, new String[] {"a", "b"});
		verify(mockMmessage).writeTo(bos, new String[] {"a", "b"});

		subject.saveChanges();
		verify(mockMmessage).saveChanges();
	}
	
	@Test
	public void testImmutability() throws IOException {
		Email email = createDummyEmailBuilder("<id>", true, false, true, true, true).buildEmail();
		final ImmutableDelegatingSMTPMessage subject = new ImmutableDelegatingSMTPMessage(emailToMimeMessage(email), "envelop@from.com");
		
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setMessageNumber(5); }}).hasMessageContaining("protected in the delegate");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setExpunged(true); }}).hasMessageContaining("protected in the delegate");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.parse(new ByteArrayInputStream(new byte[]{})); }}).hasMessageContaining("protected in the delegate");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.getContentStream(); }}).hasMessageContaining("protected in the delegate");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.updateMessageID(); }}).hasMessageContaining("protected in the delegate");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.updateHeaders(); }}).hasMessageContaining("protected in the delegate");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.createInternetHeaders(new ByteArrayInputStream(new byte[]{})); }}).hasMessageContaining("protected in the delegate");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.createMimeMessage(Session.getDefaultInstance(new Properties())); }}).hasMessageContaining("protected in the delegate");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setEnvelopeFrom("from"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setNotifyOptions(3); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setReturnOption(2); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setAllow8bitMIME(false); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setSendPartial(true); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setSubmitter("submitter"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setMailExtension("ext"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setFrom(new InternetAddress()); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setFrom("value"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setFrom(); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.addFrom(new Address[]{}); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setSender(new InternetAddress()); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setRecipients(TO, new Address[]{}); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setRecipients(TO, "addresses"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.addRecipients(TO, new Address[]{}); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.addRecipients(TO, "addresses"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setReplyTo(new Address[]{}); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setSubject("subject"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setSubject("subject", "UTF-8"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setSentDate(new Date()); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setDisposition("disposition"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setContentID("<id>"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setContentMD5("md5"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setDescription("description"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setDescription("description", "UTF-8"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setContentLanguage(new String[] {}); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setFileName("filename"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setDataHandler(mock(DataHandler.class)); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setContent("content", "type"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setText("text"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setText("text", "UTF-8"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setText("text", "UTF-8", "subtype"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setContent(mock(Multipart.class)); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setHeader("name", "value"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.addHeader("name", "value"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.removeHeader("name"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.addHeaderLine("line"); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setFlags(mock(Flags.class), false); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setRecipient(TO, new InternetAddress()); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.addRecipient(TO, new InternetAddress()); }}).hasMessage("Further mutation is not allowed");
		assertThatThrownBy(new ThrowingCallable() { public void call() { subject.setFlag(ANSWERED, true); }}).hasMessage("Further mutation is not allowed");
	}

	private static class HeaderExtractor implements Extractor<Header, String> {
		@Override
		public String extract(Header input) {
			return input.getName() + ": " + input.getValue();
		}
	}
}