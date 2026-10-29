package org.simplejavamail.converter.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ImportedFilenameTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(strings = {"<draft>.txt", "report>draft.txt", "report<draft.txt", "ordinary.txt"})
    void preservesDecodedNamesAcrossImportCopySerializationAndExport(final String name) throws Exception {
        final String eml = message(name);
        assertNames(EmailConverter.emlToEmail(eml), name);
        for (boolean eager : new boolean[] {false, true}) {
            final Email imported = mail.converter().fromEml(eml).withFetchAttachmentData(eager).toEmail();
            assertNames(imported, name);
            final MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
            assertNames(EmailConverter.mimeMessageToEmail(mime, null, eager), name);
            assertNames(mail.converter().fromMimeMessage(mime).withFetchAttachmentData(eager).toEmail(), name);
            assertNames(mail.emailBuilder().copying(imported).buildEmail(), name);
            final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(serialized)) { output.writeObject(imported); }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray()))) {
                assertNames((Email) input.readObject(), name);
            }
            assertNames(mail.converter().fromEml(mail.converter().fromEmail(imported).toEml()).toEmail(), name);
        }
        final Email exact = mail.emailBuilder().startingFromExactEml(eml.getBytes(UTF_8))
                .withEnvelopeRecipients("reader@example.org").buildEmail();
        assertNames(exact, name);
        assertThat(mail.converter().fromEmail(exact).toEmlBytes()).isEqualTo(eml.getBytes(UTF_8));
    }

    private static void assertNames(final Email email, final String name) {
        assertThat(email.getAttachments()).extracting(AttachmentResource::getName).containsExactly(name);
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("document".getBytes(UTF_8));
        assertThat(email.getEmbeddedImages()).extracting(AttachmentResource::getName).containsExactlyInAnyOrder(name, "fallback@example.org");
        assertThat(email.getEmbeddedImages().stream().filter(resource -> name.equals(resource.getName())).findFirst().get().getContentId()).isEqualTo("logo@example.org");
        assertThat(email.getEmbeddedImages().stream().filter(resource -> name.equals(resource.getName())).findFirst().get().readAllBytes()).isEqualTo("image".getBytes(UTF_8));
        assertThat(email.getEmbeddedImages().stream().filter(resource -> "fallback@example.org".equals(resource.getName())).findFirst().get().readAllBytes()).isEqualTo("fallback".getBytes(UTF_8));
    }

    private static String message(final String name) {
        return "From: sender@example.org\r\nTo: reader@example.org\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/related; boundary=b\r\n\r\n"
                + "--b\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
                + "<img src=\"cid:logo@example.org\"><img src=\"cid:fallback@example.org\">\r\n"
                + "--b\r\nContent-Type: image/png\r\nContent-Disposition: inline; filename=\"" + name + "\"\r\n"
                + "Content-ID: <logo@example.org>\r\nContent-Transfer-Encoding: base64\r\n\r\naW1hZ2U=\r\n"
                + "--b\r\nContent-Type: image/png\r\nContent-Disposition: inline\r\n"
                + "Content-ID: <fallback@example.org>\r\nContent-Transfer-Encoding: base64\r\n\r\nZmFsbGJhY2s=\r\n"
                + "--b\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"" + name + "\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nZG9jdW1lbnQ=\r\n--b--\r\n";
    }
}
