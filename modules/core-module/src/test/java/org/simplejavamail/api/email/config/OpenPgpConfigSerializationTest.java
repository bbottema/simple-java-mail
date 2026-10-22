package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenPgpConfigSerializationTest {

    @Test
    void signingRoundTripRetainsPreferencesAndDetachedSecretMaterial() throws Exception {
        final OpenPgpSigningConfig original = signing();
        final OpenPgpSigningConfig restored = (OpenPgpSigningConfig) roundTrip(original);

        assertThat(restored).isEqualTo(original).isNotSameAs(original);
        assertThat(restored.hashCode()).isEqualTo(original.hashCode());
        assertThat(restored.getSigningKeyId()).isEqualTo(42L);
        assertThat(restored.getHashAlgorithm()).isEqualTo(OpenPgpSigningConfig.HashAlgorithm.SHA512);
        restored.getSecretKeyRing()[0] = 99;
        restored.getPassphrase()[0] = 'x';
        assertThat(restored.getSecretKeyRing()).containsExactly(1, 2, 3);
        assertThat(restored.getPassphrase()).containsExactly("test-passphrase".toCharArray());
        assertThat(restored.toString()).doesNotContain("test-passphrase");
    }

    @Test
    void encryptionRoundTripRetainsEveryPublicKeyAndDetachedList() throws Exception {
        final OpenPgpEncryptionConfig original = encryption();
        final OpenPgpEncryptionConfig restored = (OpenPgpEncryptionConfig) roundTrip(original);

        assertThat(restored).isEqualTo(original).isNotSameAs(original);
        assertThat(restored.hashCode()).isEqualTo(original.hashCode());
        assertThat(restored.getSymmetricAlgorithm()).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128);
        final List<byte[]> inspected = restored.getRecipientPublicKeyRings();
        inspected.get(0)[0] = 99;
        inspected.clear();
        assertThat(restored.getRecipientPublicKeyRings()).hasSize(2);
        assertThat(restored.getRecipientPublicKeyRings().get(0)).containsExactly(4, 5);
        assertThat(restored.getRecipientPublicKeyRings().get(1)).containsExactly(6, 7);
    }

    @Test
    void defaultPreferencesAndUnprotectedSigningKeySurvive() throws Exception {
        final OpenPgpSigningConfig signing = (OpenPgpSigningConfig) roundTrip(
                OpenPgpSigningConfig.builder().secretKeyRing(new byte[]{1}).build());
        final OpenPgpEncryptionConfig encryption = (OpenPgpEncryptionConfig) roundTrip(
                OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[]{2}).build());

        assertThat(signing.getSigningKeyId()).isNull();
        assertThat(signing.getPassphrase()).isEmpty();
        assertThat(signing.getHashAlgorithm()).isEqualTo(OpenPgpSigningConfig.HashAlgorithm.SHA256);
        assertThat(encryption.getSymmetricAlgorithm()).isEqualTo(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_256);
    }

    @Test
    void serializedGraphAliasesCannotMutateRestoredConfiguration() throws Exception {
        final OpenPgpSigningConfig signing = signing();
        final OpenPgpEncryptionConfig encryption = encryption();
        final Object[] restored = (Object[]) roundTrip(new Object[]{signing, field(signing, "secretKeyRing"),
                field(signing, "passphrase"), encryption, field(encryption, "recipientPublicKeyRings")});

        ((byte[]) restored[1])[0] = 99;
        ((char[]) restored[2])[0] = 'x';
        @SuppressWarnings("unchecked")
        final List<byte[]> publicKeyAlias = (List<byte[]>) restored[4];
        publicKeyAlias.get(0)[0] = 99;
        assertThat(restored[0]).isEqualTo(signing);
        assertThat(restored[3]).isEqualTo(encryption);
    }

    @ParameterizedTest
    @MethodSource("invalidStoredState")
    void deserializationStillEnforcesStructuralConstructionInvariants(final Object config, final String name, final Object invalid) throws Exception {
        final Field field = config.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, invalid);

        assertThatThrownBy(() -> roundTrip(config)).isInstanceOf(InvalidObjectException.class);
    }

    static Stream<Object[]> invalidStoredState() {
        return Stream.of(
                new Object[]{signing(), "secretKeyRing", new byte[0]},
                new Object[]{signing(), "passphrase", null},
                new Object[]{signing(), "hashAlgorithm", null},
                new Object[]{encryption(), "recipientPublicKeyRings", new ArrayList<byte[]>()},
                new Object[]{encryption(), "recipientPublicKeyRings", null},
                new Object[]{encryption(), "recipientPublicKeyRings", Arrays.asList(new byte[]{9}, null)},
                new Object[]{encryption(), "recipientPublicKeyRings", Arrays.asList(new byte[0])},
                new Object[]{encryption(), "symmetricAlgorithm", null});
    }

    private static OpenPgpSigningConfig signing() {
        return OpenPgpSigningConfig.builder().secretKeyRing(new byte[]{1, 2, 3}).passphrase("test-passphrase")
                .signingKeyId(42).hashAlgorithm(OpenPgpSigningConfig.HashAlgorithm.SHA512).build();
    }

    private static OpenPgpEncryptionConfig encryption() {
        return OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[]{4, 5})
                .addRecipientPublicKeyRing(new byte[]{6, 7})
                .symmetricAlgorithm(OpenPgpEncryptionConfig.SymmetricAlgorithm.AES_128).build();
    }

    private static Object field(final Object config, final String name) throws Exception {
        final Field field = config.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(config);
    }

    private static Object roundTrip(final Object value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readObject();
        }
    }
}
