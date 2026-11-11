package org.simplejavamail.api.email;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.email.internal.InternalEmail;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalReceivedInspectionTest {
    @ParameterizedTest
    @ValueSource(strings = {"email-9.1.7", "email-9.3.6-plain", "email-9.3.6-builder", "email-9.3.6-notifications",
            "email-9.3.10-signed-metadata", "email-10.0.0-openpgp-omitted", "email-openpgp-details-selected-legacy-valid",
            "email-openpgp-details-selected-legacy-invalid", "email-openpgp-details-selected-legacy-missing",
            "email-openpgp-details-selected-legacy-error", "email-openpgp-details-selected-legacy-encrypted-absent",
            "email-openpgp-details-selected-legacy-plain"})
    void supportedOlderMessageSnapshotsRemainReadableWithoutInventingCurrentCoverage(final String resource) throws Exception {
        final byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream("/serialization/" + resource + ".base64")) {
            assertThat(input).isNotNull();
            bytes = Base64.getDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim());
        }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) { restored = (Email) input.readObject(); }
        assertThat(restored.getSubject()).isNotNull();
        assertUnknown(restored);
        if (restored instanceof InternalEmail) {
            final Email copied = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().copying(restored).buildEmail();
            assertThat(copied.getSubject()).isEqualTo(restored.getSubject());
            assertThat(copied.getPlainText()).isEqualTo(restored.getPlainText());
            assertUnknown(copied);
        }
    }

    private static void assertUnknown(final Email email) {
        assertThat(email.getInspection().getPlainText().getAvailability()).isEqualTo(ValueAvailability.UNKNOWN);
        assertThat(email.getInspection().getSubject().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(email.getInspection().getHTMLText().getEncryptionStatus()).isEqualTo(EncryptionStatus.UNKNOWN);
        assertThat(email.getInspection().getSmime().isInspectionComplete()).isFalse();
        assertThat(email.getInspection().getOpenPgp().isInspectionComplete()).isFalse();
        assertThat(email.getInspection().getOriginalMessage()).isNull();
        for (int index = 0; index < email.getAttachments().size(); index++) {
            assertThat(email.getInspection().getAttachment(index).isSignatureInspectionComplete()).isFalse();
            assertThat(email.getInspection().getOriginalAttachment(index)).isNull();
        }
    }
}
