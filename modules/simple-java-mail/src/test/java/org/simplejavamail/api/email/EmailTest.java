package org.simplejavamail.api.email;

import demo.DemoAppBase;
import org.junit.Test;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.GregorianCalendar;

import static java.util.Calendar.APRIL;
import static org.assertj.core.api.Assertions.assertThat;

public class EmailTest {

	@Test
	public void testSerialization() throws IOException {
		ConfigLoaderTestHelper.clearConfigProperties();

		Email e = EmailBuilder.startingBlank()
				.from("lollypop", "lol.pop@somemail.com")
				.withReplyTo("lollypop-reply", "lol.pop.reply@somemail.com")
				.withBounceTo("lollypop-bounce", "lol.pop.bounce@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.withPlainText("We should meet up!")
				.withHTMLText("<b>We should meet up!</b><img src='cid:thumbsup'>")
				.withSubject("hey")
				.withDispositionNotificationTo("simple@address.com")
				.withReturnReceiptTo("Complex Email", "simple@address.com")
				.withHeader("dummyHeader", "dummyHeaderValue")
				.buildEmail();

		OutputStream fileOut = new ByteArrayOutputStream();
		ObjectOutputStream out = new ObjectOutputStream(fileOut);
		out.writeObject(e);
		out.close();
		fileOut.close();
	}

	@Test
	public void testToStringEmpty() {
		ConfigLoaderTestHelper.clearConfigProperties();

		assertThat(EmailBuilder.startingBlank().buildEmail().toString()).isEqualTo("Email{\n"
				+ "\tid=null\n"
				+ "\tsentDate=null\n"
				+ "\tfromRecipient=null,\n"
				+ "\treplyToRecipient=null,\n"
				+ "\tbounceToRecipient=null,\n"
				+ "\ttext='null',\n"
				+ "\ttextHTML='null',\n"
				+ "\ttextCalendar='null (method: null)',\n"
				+ "\tsubject='null',\n"
				+ "\trecipients=[]\n"
				+ "}");
	}

	@Test
	public void testToStringFull() {
		ConfigLoaderTestHelper.clearConfigProperties();

		Email e = EmailBuilder.forwarding(EmailBuilder.startingBlank().buildEmail())
				.fixingMessageId("some_id")
				.fixingSentDate(new GregorianCalendar(2011, APRIL, 11).getTime())
				.from("lollypop", "lol.pop@somemail.com")
				.withReplyTo("lollypop-reply", "lol.pop.reply@somemail.com")
				.withBounceTo("lollypop-bounce", "lol.pop.bounce@somemail.com")
				.to("C.Cane", "candycane@candyshop.org")
				.withPlainText("We should meet up!")
				.withHTMLText("<b>We should meet up!</b><img src='cid:thumbsup'>")
				.withSubject("hey")
				.withDispositionNotificationTo("dispo to", "simple@address.com")
				.withReturnReceiptTo("Complex Email", "simple@address.com")
				.withHeader("dummyHeader1", "dummyHeaderValue1")
				.withHeader("dummyHeader2", "dummyHeaderValue2")
				.withCalendarText(CalendarMethod.ADD, "Calendar text")
				.signWithDomainKey("dkim_key", "dkim_domain", "dkim_selector")
				.withEmbeddedImage("the_image", DemoAppBase.produceThumbsUpImage(), "image/png")
				.withAttachment("the_attachment", DemoAppBase.produceThumbsUpImage(), "image/png")
				.buildEmail();

		assertThat(e.toString()).isEqualTo("Email{\n"
				+ "	id=some_id\n"
				+ "	sentDate=Mon Apr 11 00:00:00 CEST 2011\n"
				+ "	fromRecipient=Recipient{name='lollypop', address='lol.pop@somemail.com', type=null},\n"
				+ "	replyToRecipient=Recipient{name='lollypop-reply', address='lol.pop.reply@somemail.com', type=null},\n"
				+ "	bounceToRecipient=Recipient{name='lollypop-bounce', address='lol.pop.bounce@somemail.com', type=null},\n"
				+ "	text='We should meet up!',\n"
				+ "	textHTML='<b>We should meet up!</b><img src='cid:thumbsup'>',\n"
				+ "	textCalendar='Calendar text (method: ADD)',\n"
				+ "	subject='hey',\n"
				+ "	recipients=[Recipient{name='C.Cane', address='candycane@candyshop.org', type=To}],\n"
				+ "	applyDKIMSignature=true,\n"
				+ "		dkimSelector=dkim_selector,\n"
				+ "		dkimSigningDomain=dkim_domain,\n"
				+ "	useDispositionNotificationTo=true,\n"
				+ "		dispositionNotificationTo=Recipient{name='dispo to', address='simple@address.com', type=null},\n"
				+ "	useReturnReceiptTo=true,\n"
				+ "		returnReceiptTo=Recipient{name='Complex Email', address='simple@address.com', type=null},\n"
				+ "	headers={dummyHeader1=dummyHeaderValue1, dummyHeader2=dummyHeaderValue2},\n"
				+ "	embeddedImages=[AttachmentResource{\n"
				+ "		name='the_image',\n"
				+ "		dataSource.name=the_image,\n"
				+ "		dataSource.getContentType=image/png\n"
				+ "	}],\n"
				+ "	attachments=[AttachmentResource{\n"
				+ "		name='the_attachment',\n"
				+ "		dataSource.name=the_attachment,\n"
				+ "		dataSource.getContentType=image/png\n"
				+ "	}],\n"
				+ "	forwardingEmail=true\n"
				+ "}");
	}
}