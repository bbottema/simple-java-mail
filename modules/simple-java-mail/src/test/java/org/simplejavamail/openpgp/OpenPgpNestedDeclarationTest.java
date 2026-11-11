package org.simplejavamail.openpgp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.DecryptionStatus;

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

        final var details = received.getInspection().getOpenPgp();
        if (encrypted) {
            assertThat(details.getDecryptions()).singleElement()
                    .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.DECRYPTED));
        } else {
            assertThat(details.getDecryptions()).isEmpty();
        }
        assertThat(details.getSignatures()).singleElement().satisfies(signature -> {
            assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED);
            assertThat(signature.getSigningKeyFingerprint()).isNotBlank();
        });
        assertThat(received.getProblems()).isNotEmpty();
        assertThat(received.getInspection().getOriginalMessage().readAllBytes()).containsExactly(source);
        final Email copied = SimpleJavaMail.fromDefaults().emailBuilder().copying(received).buildEmail();
        assertThat(copied.getInspection().getOpenPgp().getSignatures()).extracting(signature -> signature.getReference())
                .containsExactlyElementsOf(details.getSignatures().stream().map(signature -> signature.getReference()).collect(java.util.stream.Collectors.toList()));
        assertThat(copied.getInspection().getOpenPgp().getDecryptions()).extracting(attempt -> attempt.getStatus())
                .containsExactlyElementsOf(details.getDecryptions().stream().map(attempt -> attempt.getStatus()).collect(java.util.stream.Collectors.toList()));
    }
}
