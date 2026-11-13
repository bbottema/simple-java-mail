package org.simplejavamail.converter.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailInspection;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.api.email.ValueInspection;
import org.simplejavamail.internal.email.DecryptionInspectionImpl;
import org.simplejavamail.internal.email.ValueInspectionImpl;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaterializedMessageInspectionTest {
    @Test
    void materializedMessageRetainsEmbeddedImageDecryptionAsPartialEvidence() {
        final UUID imageObservation = UUID.randomUUID();
        final ValueInspection unprotected = ValueInspectionImpl.builder().availability(ValueAvailability.PRESENT)
                .encryptionStatus(EncryptionStatus.NOT_ENCRYPTED).build();
        final ValueInspection image = ValueInspectionImpl.builder().availability(ValueAvailability.PRESENT)
                .encryptionStatus(EncryptionStatus.DECRYPTED)
                .decryptions(List.of(DecryptionInspectionImpl.builder().reference(imageObservation)
                        .family(ProtectionFamily.SMIME).status(DecryptionStatus.DECRYPTED)
                        .coverage(ProtectionCoverage.FULL).build())).build();
        final EmailInspection inspection = mock(EmailInspection.class, invocation ->
                invocation.getMethod().getReturnType() == ValueInspection.class ? unprotected : RETURNS_DEFAULTS.answer(invocation));
        when(inspection.getEmbeddedImage(0)).thenReturn(image);
        final Email child = mock(Email.class);
        when(child.getInspection()).thenReturn(inspection);
        when(child.getEmbeddedImages()).thenReturn(List.of(mock(AttachmentResource.class)));

        final ValueInspection materialized = MaterializedMessageInspection.describe(child, unprotected);

        assertThat(materialized.getDecryptions()).singleElement().satisfies(decryption -> {
            assertThat(decryption.getReference()).isEqualTo(imageObservation);
            assertThat(decryption.getFamily()).isEqualTo(ProtectionFamily.SMIME);
            assertThat(decryption.getStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
            assertThat(decryption.getCoverage()).isEqualTo(ProtectionCoverage.PARTIAL);
        });
        assertThat(materialized.getEncryptionStatus()).isEqualTo(EncryptionStatus.UNKNOWN);
    }
}
