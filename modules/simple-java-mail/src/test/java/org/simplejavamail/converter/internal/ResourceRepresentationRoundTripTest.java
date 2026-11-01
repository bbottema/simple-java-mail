package org.simplejavamail.converter.internal;

import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.activation.DataHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;
import testutil.ResourceRepresentationCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.EmailStartingBuilder.DEFAULT_QUOTING_MARKUP;

class ResourceRepresentationRoundTripTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final byte[] IMAGE = {0, 1, 2, 3};
    private static final byte[] OTHER = {4, 5, 6};

    @ParameterizedTest
    @EnumSource(ResourceRepresentationCase.class)
    void filenameAndCidKeepTheirOwnInterpretationThroughImportCopyAndExport(final ResourceRepresentationCase representation) throws Exception {
        final byte[] eml = source(representation);
        assertResources(EmailConverter.emlToEmail(new ByteArrayInputStream(eml)), representation);
        for (boolean eager : new boolean[] {false, true}) {
            final Email fromEml = MAIL.converter().fromEml(eml).withFetchAttachmentData(eager).toEmail();
            final MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml));
            assertResources(EmailConverter.mimeMessageToEmail(mime, null, eager), representation);
            assertResources(MAIL.converter().fromMimeMessage(mime).withFetchAttachmentData(eager).toEmail(), representation);
            assertResources(fromEml, representation);

            final Email copy = MAIL.emailBuilder().copying(fromEml).buildEmail();
            assertThat(copy.getEmbeddedImages().get(0).getDataSource()).isSameAs(fromEml.getEmbeddedImages().get(0).getDataSource());
            assertThat(copy.getAttachments().get(0).getDataSource()).isSameAs(fromEml.getAttachments().get(0).getDataSource());
            assertResources(copy, representation);
            assertResources(serializedCopy(copy), representation);
            assertResources(MAIL.converter().fromEml(MAIL.converter().fromEmail(copy).toEmlBytes())
                    .withFetchAttachmentData(eager).toEmail(), representation);
        }
        final Email exact = MAIL.emailBuilder().startingFromExactEml(eml).withEnvelopeRecipients("reader@example.org").buildEmail();
        assertResources(exact, representation);
        assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).containsExactly(eml);
    }

    @ParameterizedTest
    @EnumSource(ResourceRepresentationCase.class)
    void emailAndMimeReplyRoutesSelectTheSameResourceWithoutChangingItsLiteralName(final ResourceRepresentationCase representation)
            throws Exception {
        final Email original = MAIL.converter().fromEml(source(representation)).withFetchAttachmentData(false).toEmail();
        final Email emailReply = MAIL.emailBuilder().replyingTo(original).buildEmail();
        final Email mimeReply = MAIL.emailBuilder().replyingTo(EmailConverter.emailToMimeMessage(original)).buildEmail();

        assertReply(emailReply, representation);
        assertReply(mimeReply, representation);
        assertThat(emailReply.getEmbeddedImages().get(0).getDataSource()).isNotSameAs(original.getEmbeddedImages().get(0).getDataSource());
        assertResources(original, representation);
    }

    private static void assertReply(final Email reply, final ResourceRepresentationCase representation) {
        assertThat(reply.getHTMLText()).isEqualTo(String.format(DEFAULT_QUOTING_MARKUP, html(representation)));
        assertThat(reply.getAttachments()).isEmpty();
        assertThat(reply.getEmbeddedImages()).hasSize(1);
        assertResource(reply.getEmbeddedImages().get(0), representation.getFilename(), representation.getContentId(), IMAGE);
    }

    private static void assertResources(final Email email, final ResourceRepresentationCase representation) {
        assertThat(email.getHTMLText()).isEqualTo(html(representation));
        assertThat(email.getEmbeddedImages()).hasSize(1);
        assertResource(email.getEmbeddedImages().get(0), representation.getFilename(), representation.getContentId(), IMAGE);
        assertThat(email.getAttachments()).hasSize(1);
        assertResource(email.getAttachments().get(0), "archive-" + representation.getFilename(), representation.getDifferentContentId(), OTHER);
    }

    private static void assertResource(final AttachmentResource resource, final String name, final String cid, final byte[] bytes) {
        assertThat(resource.getName()).isEqualTo(name);
        assertThat(resource.getContentId()).isEqualTo(cid);
        assertThat(resource.readAllBytes()).containsExactly(bytes);
    }

    private static Email serializedCopy(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(email);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }

    private static String html(final ResourceRepresentationCase representation) {
        return "<p>Image</p><img src=\"" + representation.getHtmlCidReference() + "\">";
    }

    private static byte[] source(final ResourceRepresentationCase representation) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
        message.setSubject("Resource representations");
        final MimeMultipart related = new MimeMultipart("related");
        final MimeBodyPart body = new MimeBodyPart();
        body.setText(html(representation), "UTF-8", "html");
        related.addBodyPart(body);
        related.addBodyPart(resource(representation.getFilename(), representation.getContentId(), IMAGE));
        related.addBodyPart(resource("archive-" + representation.getFilename(), representation.getDifferentContentId(), OTHER));
        message.setContent(related);
        message.saveChanges();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        return bytes.toByteArray();
    }

    private static MimeBodyPart resource(final String filename, final String cid, final byte[] bytes) throws Exception {
        final MimeBodyPart resource = new MimeBodyPart();
        resource.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, "image/png")));
        resource.setFileName(filename);
        resource.setDisposition(Part.INLINE);
        resource.setContentID('<' + cid + '>');
        return resource;
    }
}
