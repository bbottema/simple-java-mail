package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.SIGNED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.SIGNED_ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class RetainedDetachedSmimeSignatureTest {

    @ParameterizedTest(name = "merge signed content={0}")
    @ValueSource(booleans = {true, false})
    void decryptedOutlookSignaturesRemainVerifiableInTheirRetainedBytes(final boolean mergeContent) throws Exception {
        final Path source = Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/pkcs12/test messages/S_MIME test message signed & encrypted.msg");
        final BorrowedInput input = new BorrowedInput(Files.readAllBytes(source));
        final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        final EmailPopulatingBuilder builder = mail.converter().fromOutlookMsg(input)
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmailBuilder();
        if (!mergeContent) {
            builder.notMergingSingleSMIMESignedAttachment();
        }
        final Email email = builder.buildEmail();

        assertThat(input.closed).isFalse();
        assertRetainedSignature(email, mergeContent);
        assertRetainedSignature(mail.emailBuilder().copying(email).buildEmail(), mergeContent);
        assertRetainedSignature(roundTrip(email), mergeContent);
        assertRetainedSignature(EmailConverter.outlookMsgToEmail(source.toFile(), loadPkcs12KeyStore()), true);
    }

    private static void assertRetainedSignature(final Email email, final boolean merged) throws Exception {
        final AttachmentResource generatedEml = email.getDecryptedAttachments().stream()
                .filter(resource -> "signed-email.eml".equals(resource.getName())).findFirst().orElseThrow();
        final byte[] retainedBytes = generatedEml.readAllBytes();
        final MimeMessage retained = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(retainedBytes));

        assertThat(retained.isMimeType("multipart/signed")).as("Retained EML must contain the signed entity, not only its clear content").isTrue();
        assertThat(SmimeCryptoSupport.checkSignature(retained)).as("Actual retained bytes independently verify").isTrue();
        assertThat(retained.getContentType()).containsIgnoringCase("application/pkcs7-signature");

        assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SIGNED_ENCRYPTED);
        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
        assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(DECRYPTED);
        // MSG supplies its own representation; this EML is generated from its decrypted signed entity.
        assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).isNull();
        if (merged) {
            assertThat(email.getAttachments()).extracting("name")
                    .containsExactlyInAnyOrder("smime.p7m", "smime.p7s", "03-07-2005 errata SharnErrata.pdf");
            assertThat(email.getDecryptedAttachments()).extracting("name")
                    .containsExactlyInAnyOrder("smime.p7s", "signed-email.eml", "03-07-2005 errata SharnErrata.pdf");
            assertThat(email.getPlainText()).contains("For testing purposes in the Simple Java Mail project");
            assertThat(email.getHTMLText()).contains("cid:part1.0B245DA7.F5872CD5@bennybottema.com");
            assertThat(email.getEmbeddedImages()).hasSize(1);
        } else {
            assertThat(email.getAttachments()).extracting("name").containsExactly("smime.p7m");
            assertThat(email.getDecryptedAttachments()).extracting("name").containsExactly("signed-email.eml");
            assertThat(email.getEmbeddedImages()).isEmpty();
        }

        final Email signedView = email.getSmimeSignedEmail();
        assertThat(signedView).isNotNull();
        assertThat(signedView.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SIGNED);
        assertThat(signedView.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
        assertThat(signedView.getAttachments()).extracting("name")
                .containsExactlyInAnyOrder("smime.p7s", "03-07-2005 errata SharnErrata.pdf");
        assertThat(signedView.getEmbeddedImages()).hasSize(1);
        assertThat(signedView.getPlainText()).contains("For testing purposes in the Simple Java Mail project");
        assertThat(signedView.getHTMLText()).contains("cid:part1.0B245DA7.F5872CD5@bennybottema.com");

        final BorrowedInput exportedInput = new BorrowedInput(retainedBytes);
        final Email reimported = SimpleJavaMail.fromDefaults().converter().fromEml(exportedInput).toEmail();
        assertThat(exportedInput.closed).isFalse();
        assertThat(reimported.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SIGNED);
        assertThat(reimported.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
        assertThat(reimported.getPlainText()).contains("For testing purposes in the Simple Java Mail project");
        assertThat(reimported.getAttachments()).extracting("name")
                .containsExactlyInAnyOrder("smime.p7s", "03-07-2005 errata SharnErrata.pdf");
        assertThat(reimported.getEmbeddedImages()).hasSize(1);
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(email);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }

    private static final class BorrowedInput extends ByteArrayInputStream {
        private boolean closed;

        private BorrowedInput(final byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
}
