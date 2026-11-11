package org.simplejavamail.internal.smimesupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.assertOriginal;

/** Real received input replaces constructor tests of the removed mutable flattened S/MIME model. */
class ReceivedOriginalOwnershipTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(testutil.ConfigLoaderTestHelper.emptyConfig());

    @Test
    void largeCompleteOriginalIsDetachedFromSuppliedAndReturnedArraysAndSurvivesCopies() {
        byte[] body = new byte[2 * 1024 * 1024];
        Arrays.fill(body, (byte) '*');
        byte[] header = "From: sender@example.org\r\nContent-Type: text/plain; charset=us-ascii\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] source = new byte[header.length + body.length];
        System.arraycopy(header, 0, source, 0, header.length);
        System.arraycopy(body, 0, source, header.length, body.length);
        byte[] expected = source.clone();

        Email received = MAIL.converter().fromEml(source).toEmail();
        source[0] ^= 1;
        byte[] returned = received.getInspection().getOriginalMessage().readAllBytes();
        returned[1] ^= 1;
        assertOriginal(received, expected);
        assertThat(received.getPlainText()).hasSize(body.length);
        for (int index = 0; index < 3; index++) {
            received = MAIL.emailBuilder().copying(received).buildEmail();
            assertOriginal(received, expected);
            assertThat(received.getPlainText()).hasSize(body.length);
        }
    }

    @Test
    void supportedSnapshotKeepsActualSignaturesOriginalBytesAndUnmodifiableReadFacts() throws Exception {
        Path fixture = Path.of(determineResourceFolder("simple-java-mail"), "test/resources/smime/openssl-detached-signed.eml.b64");
        byte[] source = Base64.getMimeDecoder().decode(Files.readAllBytes(fixture));
        Email received = MAIL.converter().fromEml(source).toEmail();
        Email restored = roundTrip(MAIL.emailBuilder().copying(received).buildEmail());

        assertOriginal(restored, source);
        assertThat(restored.getPlainText()).contains("OpenSSL independent body");
        assertThat(restored.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(restored.getInspection().getSmime().getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
        assertThatThrownBy(() -> restored.getInspection().getSmime().getSignatures().clear()).isInstanceOf(UnsupportedOperationException.class);
        byte[] returned = restored.getInspection().getOriginalMessage().readAllBytes();
        returned[0] ^= 1;
        assertOriginal(restored, source);
    }

    private static Email roundTrip(Email email) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }
}
