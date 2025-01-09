package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MimeMessageHelperTest {
	
	@BeforeEach
	public void setup() {
		ConfigLoaderTestHelper.clearConfigProperties();
		ModuleLoader._forceRecheckModule();
	}

	@Test
	public void determineResourceName1()
			throws IOException {
		AttachmentResource resource1 = new AttachmentResource(null, null,getDataSource("blahblah"));
		assertThat(MimeMessageHelper.determineResourceName(resource1, Part.ATTACHMENT, true, false)).isEqualTo("blahblah");
		assertThat(MimeMessageHelper.determineResourceName(resource1, Part.INLINE, true, false)).isEqualTo("blahblah");
	}

	@Test
	public void determineResourceName2()
			throws IOException {
		AttachmentResource resource2 = new AttachmentResource(null, null, getDataSource("blahblah.txt"));
		assertThat(MimeMessageHelper.determineResourceName(resource2, Part.ATTACHMENT, true, false)).isEqualTo("blahblah.txt");
		assertThat(MimeMessageHelper.determineResourceName(resource2, Part.INLINE, true, false)).isEqualTo("blahblah.txt");
	}
	
	@Test
	public void determineResourceName3()
			throws IOException {
		AttachmentResource resource3 = new AttachmentResource("the resource", null, getDataSource(null));
		assertThat(MimeMessageHelper.determineResourceName(resource3, Part.ATTACHMENT, true, false)).isEqualTo("the resource");
		assertThat(MimeMessageHelper.determineResourceName(resource3, Part.INLINE, true, false)).isEqualTo("the resource");
	}
	
	@Test
	public void determineResourceName4()
			throws IOException {
		AttachmentResource resource4 = new AttachmentResource("the resource", null, getDataSource("blahblah.txt"));
		assertThat(MimeMessageHelper.determineResourceName(resource4, Part.ATTACHMENT, true, false)).isEqualTo("the resource.txt");
		assertThat(MimeMessageHelper.determineResourceName(resource4, Part.INLINE, true, false)).isEqualTo("the resource");
	}
	
	@Test
	public void determineResourceName5()
			throws IOException {
		AttachmentResource resource5 = new AttachmentResource("the resource", null, getDataSource("blahblah"));
		assertThat(MimeMessageHelper.determineResourceName(resource5, Part.ATTACHMENT, true, false)).isEqualTo("the resource");
		assertThat(MimeMessageHelper.determineResourceName(resource5, Part.INLINE, true, false)).isEqualTo("the resource");
	}
	
	@Test
	public void determineResourceName6()
			throws IOException {
		AttachmentResource resource6 = new AttachmentResource("the resource.txt", null, getDataSource("blahblah.txt"));
		assertThat(MimeMessageHelper.determineResourceName(resource6, Part.ATTACHMENT, true, false)).isEqualTo("the resource.txt");
		assertThat(MimeMessageHelper.determineResourceName(resource6, Part.INLINE, true, false)).isEqualTo("the resource.txt");
	}
	
	@Test
	public void determineResourceName7()
			throws IOException {
		AttachmentResource resource7 = new AttachmentResource("the resource.txt", null, getDataSource("blahblah"));
		assertThat(MimeMessageHelper.determineResourceName(resource7, Part.ATTACHMENT, true, false)).isEqualTo("the resource.txt");
		assertThat(MimeMessageHelper.determineResourceName(resource7, Part.INLINE, true, false)).isEqualTo("the resource.txt");
	}
	
	@Test
	public void determineResourceName_ignoreExtensionFromResource()
			throws IOException {
		AttachmentResource resource7 = new AttachmentResource("the resource.txt", null, getDataSource("blahblah.1/www/get?id=3"));
		assertThat(MimeMessageHelper.determineResourceName(resource7, Part.ATTACHMENT, true, false)).isEqualTo("the resource.txt");
		assertThat(MimeMessageHelper.determineResourceName(resource7, Part.INLINE, true, false)).isEqualTo("the resource.txt");
	}
	
	private ByteArrayDataSource getDataSource(@Nullable String name)
			throws IOException {
		ByteArrayDataSource ds = new ByteArrayDataSource("", "text/text");
		ds.setName(name);
		return ds;
	}
	
	@Test
	public void testSignMessageWithDKIM_ShouldFailSpecificallyBecauseItWillTryToSign()
			throws IOException, ClassNotFoundException {
		final Email email = EmailHelper.createDummyEmailBuilder(true, false, false, true, false, false)
				.signWithDomainKey(DkimConfig.builder()
						.dkimPrivateKeyData("dummykey")
						.dkimSigningDomain("moo.com")
						.dkimSelector("selector")
						.build())
				.buildEmail();
		
		assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
				.isInstanceOf(Class.forName("org.simplejavamail.internal.dkimsupport.DKIMSigningException"))
				.hasMessage("Error signing MimeMessage with DKIM");
	}

	@Test
	public void filenameWithSpaceEncoding() throws IOException, MessagingException {
		final String fileName = "file name.txt";
		final Email email = EmailHelper.createDummyEmailBuilder(true, true, false, false, false, false)
				.clearAttachments().withAttachment(fileName, "abc".getBytes(), "text/plain").buildEmail();
		final MimeMessage mimeMessage = EmailConverter.emailToMimeMessage(email);
		final BodyPart bodyPart = ((MimeMultipart) mimeMessage.getContent()).getBodyPart(1);
		ContentType ct = new ContentType(bodyPart.getHeader("Content-Type")[0]);
		assertThat(ct.getParameter("filename")).isEqualTo(fileName);
		assertThat(bodyPart.getFileName()).isEqualTo(fileName);
	}
}