package org.simplejavamail.internal.smimesupport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.*;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class RetainedDetachedSmimeSignatureTest {
    @ParameterizedTest(name = "fluent={0}")
    @ValueSource(booleans = {true, false})
    void retainedOriginalNativeBytesKeepTheirDetachedSignatureAcrossCopySnapshotAndReimport(boolean fluent) throws Exception {
        Path source = Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/pkcs12/test messages/S_MIME test message signed & encrypted.msg");
        byte[] original = Files.readAllBytes(source);
        BorrowedInput input = new BorrowedInput(original);
        SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        Email email = fluent ? mail.converter().fromOutlookMsg(input).withSmimeDecryption(loadPkcs12KeyStore()).toEmail()
                : EmailConverter.outlookMsgToEmail(source.toFile(), loadPkcs12KeyStore());
        assertThat(input.closed).isFalse();
        assertRetainedSignature(email, original);
        assertRetainedSignature(mail.emailBuilder().copying(email).buildEmail(), original);
        assertRetainedSignature(roundTrip(email), original);
    }

    private static void assertRetainedSignature(Email email, byte[] original) throws Exception {
        assertOriginal(email, original);
        assertPlainProtection(email, SignatureStatus.VERIFIED, EncryptionStatus.DECRYPTED);
        assertSignatureObservation(email, SignatureVerification.VERIFIED);
        assertThat(email.getAttachments()).extracting("name").containsExactly("03-07-2005 errata SharnErrata.pdf");
        assertThat(email.getInspection().getAttachment(0).getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(email.getEmbeddedImages()).hasSize(1);
        assertThat(email.getPlainText()).contains("For testing purposes in the Simple Java Mail project");
        assertThat(email.getHTMLText()).contains("cid:part1.0B245DA7.F5872CD5@bennybottema.com");
        assertThat(email.getInspection().getOriginalAttachment(0).readAllBytes())
                .containsExactly(email.getAttachments().get(0).readAllBytes());

        // The retained original is MSG, not generated EML. Its unchanged ciphertext still opens to an independently verifiable signature.
        BorrowedInput again = new BorrowedInput(email.getInspection().getOriginalMessage().readAllBytes());
        Email reimported = SimpleJavaMail.fromDefaults().converter().fromOutlookMsg(again).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(again.closed).isFalse();
        assertPlainProtection(reimported, SignatureStatus.VERIFIED, EncryptionStatus.DECRYPTED);
        assertSignatureObservation(reimported, SignatureVerification.VERIFIED);
        assertThat(reimported.getAttachments()).extracting("name").containsExactly("03-07-2005 errata SharnErrata.pdf");
        assertThat(reimported.getAttachments().get(0).readAllBytes()).containsExactly(email.getAttachments().get(0).readAllBytes());
    }

    private static Email roundTrip(Email email) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) input.readObject(); }
    }

    private static final class BorrowedInput extends ByteArrayInputStream {
        private boolean closed;
        private BorrowedInput(byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
}
