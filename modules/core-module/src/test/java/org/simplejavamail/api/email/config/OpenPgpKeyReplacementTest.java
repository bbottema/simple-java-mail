package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenPgpKeyReplacementTest {

    @ParameterizedTest
    @MethodSource("nullReplacements")
    void rejectedReplacementPreservesEveryPreviousKeyAndCipher(final List<byte[]> replacement) {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = configuredBuilder();
        final OpenPgpEncryptionConfig original = builder.build();

        assertThatThrownBy(() -> builder.recipientPublicKeyRings(replacement)).isInstanceOf(NullPointerException.class);

        assertThat(builder.build()).isEqualTo(original);
        assertThat(builder.getRecipientPublicKeyRings().get(0)).containsExactly(1, 2);
        assertThat(builder.getRecipientPublicKeyRings().get(1)).containsExactly(3, 4);
        assertThat(builder.getSymmetricAlgorithm()).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
    }

    static Stream<List<byte[]>> nullReplacements() {
        return Stream.of(null, Collections.singletonList(null), Arrays.asList(new byte[]{5, 6}, null));
    }

    @Test
    void usableReplacementDetachesItsListAndArraysAndRetainsEarlierSnapshots() {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = configuredBuilder();
        final OpenPgpEncryptionConfig original = builder.build();
        final byte[] first = {5, 6};
        final byte[] second = {7, 8};
        final List<byte[]> replacement = new ArrayList<>(Arrays.asList(first, second));

        assertThat(builder.recipientPublicKeyRings(replacement)).isSameAs(builder);
        final OpenPgpEncryptionConfig replaced = builder.build();
        first[0] = 99;
        second[0] = 99;
        replacement.clear();
        final List<byte[]> inspected = builder.getRecipientPublicKeyRings();
        inspected.get(0)[0] = 99;
        inspected.clear();

        assertThat(builder.build()).isEqualTo(replaced);
        assertThat(replaced.getRecipientPublicKeyRings()).hasSize(2);
        assertThat(replaced.getRecipientPublicKeyRings().get(0)).containsExactly(5, 6);
        assertThat(replaced.getRecipientPublicKeyRings().get(1)).containsExactly(7, 8);
        assertThat(replaced.getSymmetricAlgorithm()).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        assertThat(original.getRecipientPublicKeyRings().get(0)).containsExactly(1, 2);
        assertThat(original.getRecipientPublicKeyRings().get(1)).containsExactly(3, 4);
    }

    @Test
    void emptyReplacementClearsOnlyTheKeysAndCanBeRepaired() {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = configuredBuilder();
        final OpenPgpEncryptionConfig original = builder.build();

        builder.recipientPublicKeyRings(Collections.emptyList());

        assertThat(builder.getRecipientPublicKeyRings()).isEmpty();
        assertThat(builder.getSymmetricAlgorithm()).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("At least one recipient OpenPGP public key ring is required");
        builder.addRecipientPublicKeyRing(new byte[]{9});
        assertThat(builder.build().getRecipientPublicKeyRings().get(0)).containsExactly(9);
        assertThat(original.getRecipientPublicKeyRings()).hasSize(2);
    }

    @Test
    void additiveAndReplacementSettersCollectEmptyKeysUntilBuild() {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder additive = configuredBuilder()
                .clearRecipientPublicKeyRings().addRecipientPublicKeyRing(new byte[0]);
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder replacement = configuredBuilder()
                .recipientPublicKeyRings(Collections.singletonList(new byte[0]));

        assertThat(additive.getRecipientPublicKeyRings()).hasSize(1);
        assertThat(replacement.getRecipientPublicKeyRings()).hasSize(1);
        assertThat(additive.getRecipientPublicKeyRings().get(0)).isEmpty();
        assertThat(replacement.getRecipientPublicKeyRings().get(0)).isEmpty();
        assertThatThrownBy(additive::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenPGP public key rings cannot be null or empty");
        assertThatThrownBy(replacement::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenPGP public key rings cannot be null or empty");

        replacement.recipientPublicKeyRings(Collections.singletonList(new byte[]{9}));
        assertThat(replacement.build().getRecipientPublicKeyRings().get(0)).containsExactly(9);
        assertThat(replacement.getSymmetricAlgorithm()).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
    }

    @Test
    void nullAdditionPreservesExistingChoices() {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = configuredBuilder();
        final OpenPgpEncryptionConfig original = builder.build();

        assertThatThrownBy(() -> builder.addRecipientPublicKeyRing(null)).isInstanceOf(NullPointerException.class);

        assertThat(builder.build()).isEqualTo(original);
    }

    @Test
    void completeConfigurationStillRejectsAnEmptyRingAmongUsableRings() {
        final OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder builder = configuredBuilder()
                .recipientPublicKeyRings(Arrays.asList(new byte[]{9}, new byte[0]));

        assertThat(builder.getRecipientPublicKeyRings()).hasSize(2);
        assertThat(builder.getRecipientPublicKeyRings().get(0)).containsExactly(9);
        assertThat(builder.getRecipientPublicKeyRings().get(1)).isEmpty();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenPGP public key rings cannot be null or empty");
    }

    private static OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder configuredBuilder() {
        return OpenPgpEncryptionConfig.builder()
                .addRecipientPublicKeyRing(new byte[]{1, 2})
                .addRecipientPublicKeyRing(new byte[]{3, 4})
                .symmetricAlgorithm(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
    }
}
