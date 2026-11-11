package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.api.email.ValueInspection;
import org.simplejavamail.internal.email.DecryptionInspectionImpl;
import org.simplejavamail.internal.email.EmailInspection;
import org.simplejavamail.internal.protection.ProtectionLayerResult;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectionScopeTest {

    @Test
    void deferredSignaturePreservesCoveringDecryptionOfPresentPayload() {
        final EmailSourcePart source = EmailInspection.part(EmailInspection.origin(null), "source/parts[0]",
                List.of(), List.of(), List.of(), null, null);
        final DecryptionInspectionImpl decryption = DecryptionInspectionImpl.builder().family(ProtectionFamily.SMIME)
                .status(DecryptionStatus.DECRYPTED).coverage(ProtectionCoverage.FULL).build();
        final ProtectionScope covering = ProtectionScope.plain().withLayer(ProtectionLayerResult.builder()
                .recognized(true).opened(true).inspectionComplete(true).decryptions(List.of(decryption))
                .effectiveMimeMessage(new MimeMessage(Session.getInstance(new Properties()))).build(), source);

        final ProtectionScope deferred = covering.deferredSignature();
        final ValueInspection retained = deferred.inspect(List.of(source), ValueAvailability.PRESENT);
        assertThat(retained.getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(retained.getDecryptions()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getReference()).isEqualTo(decryption.getReference());
            assertThat(attempt.getCoverage()).isEqualTo(ProtectionCoverage.FULL);
        });
        assertThat(retained.isSignatureInspectionComplete()).isFalse();
        assertThat(retained.getSignatures()).isEmpty();

        final ValueInspection unavailable = deferred.inspect(List.of(source), ValueAvailability.UNAVAILABLE);
        assertThat(unavailable.getEncryptionStatus()).isEqualTo(EncryptionStatus.UNKNOWN);
        assertThat(unavailable.getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getCoverage()).isEqualTo(ProtectionCoverage.UNKNOWN));
        assertThat(covering.incomplete().inspect(List.of(source), ValueAvailability.PRESENT).getEncryptionStatus())
                .isEqualTo(EncryptionStatus.UNKNOWN);
        assertThat(covering.deferredDecryption(ProtectionFamily.SMIME, source).inspect(List.of(source), ValueAvailability.PRESENT)
                .getEncryptionStatus()).isEqualTo(EncryptionStatus.ENCRYPTED);
    }
}
