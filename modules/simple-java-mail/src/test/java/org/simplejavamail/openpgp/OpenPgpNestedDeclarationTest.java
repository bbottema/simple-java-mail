package org.simplejavamail.openpgp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

class OpenPgpNestedDeclarationTest {

    private static final byte[] MALFORMED_ENTITY = ("Content-Type: not-a-content-type\r\n\r\n"
            + "This signed content has an uninterpretable MIME declaration.\r\n").getBytes(US_ASCII);

    @BeforeAll
    static void initializeKeys() throws Exception {
        OpenPgpSignatureFixtures.initialize();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUninterpretableInnerDeclarationRetainsEstablishedOuterFacts(final boolean encrypted) throws Exception {
        final byte[] source = encrypted
                ? OpenPgpSignatureFixtures.combined(MALFORMED_ENTITY, -1, OpenPgpSignatureFixtures.first)
                : OpenPgpSignatureFixtures.detached(MALFORMED_ENTITY, -1, OpenPgpSignatureFixtures.first);

        final Email received = SimpleJavaMail.fromDefaults().converter().fromEml(source)
                .withOpenPgpReceiveConfig(OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first)).toEmail();

        final OriginalOpenPgpDetails details = received.getOriginalOpenPgpDetails();
        assertThat(details.getOpenPgpMode()).isEqualTo(encrypted
                ? OriginalOpenPgpDetails.OpenPgpMode.SIGNED_ENCRYPTED : OriginalOpenPgpDetails.OpenPgpMode.SIGNED);
        assertThat(details.getDecryptionStatus()).isEqualTo(encrypted
                ? OriginalOpenPgpDetails.DecryptionStatus.DECRYPTED : OriginalOpenPgpDetails.DecryptionStatus.NOT_ENCRYPTED);
        assertThat(details.getSignatures()).singleElement().satisfies(signature -> {
            assertThat(signature.getStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.VALID);
            assertThat(signature.getSignerFingerprint()).isNotBlank();
        });
        assertThat(details.getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.ERROR);
        assertThat(details.getFailureReason()).isNotBlank();
        assertThat(details.getOriginalProtectedMessage()).containsExactly(source);
        final Email copied = SimpleJavaMail.fromDefaults().emailBuilder().copying(received).buildEmail();
        assertThat(copied.getOriginalOpenPgpDetails().getSignatures()).isEqualTo(details.getSignatures());
        assertThat(copied.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(details.getDecryptionStatus());
    }
}
