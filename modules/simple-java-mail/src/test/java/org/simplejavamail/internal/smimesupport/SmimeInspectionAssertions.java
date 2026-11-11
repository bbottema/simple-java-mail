package org.simplejavamail.internal.smimesupport;

import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.EmailSourcePart;

import static org.assertj.core.api.Assertions.assertThat;

/** Assertions keep selected plain-text coverage separate from family processing and retained raw input. */
final class SmimeInspectionAssertions {
    private SmimeInspectionAssertions() { }

    static void assertPlainProtection(final Email email, final SignatureStatus signature, final EncryptionStatus encryption) {
        assertThat(email.getPlainText()).isNotNull();
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(signature);
        assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(encryption);
    }

    static void assertSignatureObservation(final Email email, final SignatureVerification status) {
        assertThat(email.getInspection().getSmime().getSignatures()).anySatisfy(signature -> {
            assertThat(signature.getStatus()).isEqualTo(status);
            assertThat(signature.getSources()).isNotEmpty();
            assertThat(email.getInspection().getPlainText().getSignatures()).anySatisfy(selected ->
                    assertThat(selected.getObservation().getReference()).isEqualTo(signature.getReference()));
        });
    }

    static void assertDecryptionObservation(final Email email, final DecryptionStatus status) {
        assertThat(email.getInspection().getSmime().getDecryptions()).anySatisfy(decryption -> {
            assertThat(decryption.getStatus()).isEqualTo(status);
            assertThat(decryption.getSources()).isNotEmpty();
        });
    }

    static void assertUnopenedFamily(final Email email, final DecryptionStatus status) {
        assertThat(email.getInspection().getSmime().getSignatures()).isEmpty();
        assertThat(email.getInspection().getSmime().isInspectionComplete()).isFalse();
        assertDecryptionObservation(email, status);
    }

    static void assertOriginal(final Email email, final byte[] original) {
        assertThat(email.getInspection().getOriginalMessage()).isNotNull();
        assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(original);
    }

    static void assertFileOccurrenceDistinctFromEnvelope(final Email email, final int attachmentIndex, final String filename) {
        final var selected = email.getInspection().getAttachment(attachmentIndex);
        assertThat(selected.getSources()).anySatisfy(fileSource -> {
            assertThat(fileSource).isInstanceOf(EmailSourcePart.class);
            assertThat(((EmailSourcePart) fileSource).getHeaders()).anySatisfy(header -> {
                assertThat(header.getName()).isEqualToIgnoringCase("Content-Disposition");
                assertThat(header.getRawValue()).contains(filename);
            });
            assertThat(selected.getSignatures()).anySatisfy(signature -> {
                assertThat(signature.getObservation().getSources()).isNotEmpty();
                assertThat(signature.getObservation().getSources()).noneMatch(envelopeSource -> sameOccurrence(fileSource, envelopeSource));
            });
        });
    }

    private static boolean sameOccurrence(final EmailSourceElement first, final EmailSourceElement second) {
        return first.getOrigin().getReference().equals(second.getOrigin().getReference()) && first.getLocation().equals(second.getLocation());
    }
}
