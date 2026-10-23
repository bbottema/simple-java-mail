package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenPgpReceiveDraftTest {

    @Test
    void bothPassphraseOverloadsRetainEmptyDecryptionKeysUntilConstruction() {
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder arrayPassphrase = OpenPgpReceiveConfig.builder();
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder textPassphrase = OpenPgpReceiveConfig.builder();

        assertThat(arrayPassphrase.addDecryptionKeyRing(new byte[0], new char[0])).isSameAs(arrayPassphrase);
        assertThat(textPassphrase.addDecryptionKeyRing(new byte[0], "unfinished-passphrase")).isSameAs(textPassphrase);

        assertThat(arrayPassphrase.getDecryptionKeyRings().get(0).getKeyRing()).isEmpty();
        assertThat(arrayPassphrase.getDecryptionKeyRings().get(0).getPassphrase()).isEmpty();
        assertThat(textPassphrase.getDecryptionKeyRings().get(0).getKeyRing()).isEmpty();
        assertThat(textPassphrase.getDecryptionKeyRings().get(0).getPassphrase()).isEqualTo("unfinished-passphrase".toCharArray());
        assertThat(textPassphrase.getDecryptionKeyRings().get(0).toString()).doesNotContain("unfinished-passphrase");
        assertThatThrownBy(arrayPassphrase::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenPGP secret key ring cannot be empty");
        assertThatThrownBy(textPassphrase::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenPGP secret key ring cannot be empty");
    }

    @Test
    void failedConstructionAndRepairKeepEarlierConfigurationsAndInspectionsIntact() {
        final AtomicInteger callbackCalls = new AtomicInteger();
        final OpenPgpReceiveConfig.PassphraseProvider provider = keyId -> {
            callbackCalls.incrementAndGet();
            return new char[] {'p'};
        };
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder builder = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(new byte[] {7}).addDecryptionKeyRing(new byte[] {1, 2}, "original")
                .passphraseProvider(provider);
        final OpenPgpReceiveConfig completed = builder.build();
        final List<OpenPgpReceiveConfig.SecretKeyRing> inspected = builder.getDecryptionKeyRings();

        builder.addDecryptionKeyRing(new byte[0], "unfinished");
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.getDecryptionKeyRings()).hasSize(2);
        builder.clearDecryptionKeyRings().addDecryptionKeyRing(new byte[] {3, 4}, "replacement");
        final OpenPgpReceiveConfig repaired = builder.build();

        assertThat(repaired.getDecryptionKeyRings().get(0).getKeyRing()).containsExactly(3, 4);
        assertThat(repaired.getVerificationKeyRings().get(0)).containsExactly(7);
        assertThat(repaired.getPassphraseProvider()).isSameAs(provider);
        assertThat(completed.getDecryptionKeyRings()).hasSize(1);
        assertThat(completed.getDecryptionKeyRings().get(0).getKeyRing()).containsExactly(1, 2);
        assertThat(completed.getDecryptionKeyRings().get(0).getPassphrase()).isEqualTo("original".toCharArray());
        assertThat(inspected).hasSize(1);
        assertThat(inspected.get(0).getKeyRing()).containsExactly(1, 2);
        assertThat(inspected.get(0).getPassphrase()).isEqualTo("original".toCharArray());
        assertThat(callbackCalls).hasValue(0);
    }

    @Test
    void inputArraysAndInspectionResultsAreDetachedFromDraftAndCompletedChoices() {
        final byte[] verificationKey = {9, 10};
        final byte[] decryptionKey = {11, 12};
        final char[] passphrase = {'s', 'e', 'c', 'r', 'e', 't'};
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder builder = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(verificationKey).addDecryptionKeyRing(decryptionKey, passphrase);
        final OpenPgpReceiveConfig completed = builder.build();
        verificationKey[0] = 99;
        decryptionKey[0] = 99;
        passphrase[0] = 'x';

        final List<OpenPgpReceiveConfig.SecretKeyRing> inspected = builder.getDecryptionKeyRings();
        inspected.get(0).getKeyRing()[0] = 99;
        inspected.get(0).getPassphrase()[0] = 'x';
        inspected.clear();
        builder.getVerificationKeyRings().get(0)[0] = 99;
        completed.getDecryptionKeyRings().get(0).getKeyRing()[0] = 99;
        completed.getDecryptionKeyRings().get(0).getPassphrase()[0] = 'x';
        completed.getDecryptionKeyRings().clear();
        completed.getVerificationKeyRings().get(0)[0] = 99;
        builder.clearDecryptionKeyRings().clearVerificationKeyRings();

        assertThat(completed.getDecryptionKeyRings()).hasSize(1);
        assertThat(completed.getDecryptionKeyRings().get(0).getKeyRing()).containsExactly(11, 12);
        assertThat(completed.getDecryptionKeyRings().get(0).getPassphrase()).isEqualTo("secret".toCharArray());
        assertThat(completed.getVerificationKeyRings().get(0)).containsExactly(9, 10);
        assertThat(builder.getDecryptionKeyRings()).isEmpty();
        assertThat(builder.getVerificationKeyRings()).isEmpty();
    }

    @Test
    void nullInputsAreRejectedWithoutChangingStoredChoices() {
        final OpenPgpReceiveConfig.PassphraseProvider provider = keyId -> {
            throw new AssertionError("Callback must not run while collecting or inspecting choices");
        };
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder builder = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(new byte[] {7}).addDecryptionKeyRing(new byte[] {1, 2}, "secret")
                .passphraseProvider(provider);

        assertThatThrownBy(() -> builder.addDecryptionKeyRing(null, new char[0])).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addDecryptionKeyRing(new byte[] {3}, (char[]) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addDecryptionKeyRing(null, "secret")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addDecryptionKeyRing(new byte[] {3}, (String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.addVerificationKeyRing(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.passphraseProvider(null)).isInstanceOf(NullPointerException.class);

        assertThat(builder.getDecryptionKeyRings()).hasSize(1);
        assertThat(builder.getDecryptionKeyRings().get(0).getKeyRing()).containsExactly(1, 2);
        assertThat(builder.getDecryptionKeyRings().get(0).getPassphrase()).isEqualTo("secret".toCharArray());
        assertThat(builder.getVerificationKeyRings().get(0)).containsExactly(7);
        assertThat(builder.getPassphraseProvider()).isSameAs(provider);
        assertThat(builder.build().getDecryptionKeyRings()).hasSize(1);
    }

    @Test
    void clearingChoicesRemainsIndependentWhileDecryptionMaterialIsUnfinished() {
        final OpenPgpReceiveConfig.PassphraseProvider provider = keyId -> {
            throw new AssertionError("Callback must not run while collecting or inspecting choices");
        };
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder builder = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(new byte[] {7}).addDecryptionKeyRing(new byte[0], "unfinished")
                .passphraseProvider(provider);

        assertThat(builder.clearVerificationKeyRings()).isSameAs(builder);
        assertThat(builder.getVerificationKeyRings()).isEmpty();
        assertThat(builder.getDecryptionKeyRings().get(0).getKeyRing()).isEmpty();
        assertThat(builder.getPassphraseProvider()).isSameAs(provider);
        assertThat(builder.clearPassphraseProvider()).isSameAs(builder);
        assertThat(builder.getPassphraseProvider()).isNull();
        assertThat(builder.getDecryptionKeyRings().get(0).getPassphrase()).isEqualTo("unfinished".toCharArray());
        builder.addVerificationKeyRing(new byte[] {8}).passphraseProvider(provider);
        assertThat(builder.clearDecryptionKeyRings()).isSameAs(builder);

        final OpenPgpReceiveConfig completed = builder.build();
        assertThat(completed.getDecryptionKeyRings()).isEmpty();
        assertThat(completed.getVerificationKeyRings().get(0)).containsExactly(8);
        assertThat(completed.getPassphraseProvider()).isSameAs(provider);
    }

    @Test
    void unfinishedVerificationKeysStillFailAtConstructionAndCanBeCleared() {
        final OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder builder = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(new byte[0]).addDecryptionKeyRing(new byte[] {1}, new char[0]);

        assertThat(builder.getVerificationKeyRings().get(0)).isEmpty();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenPGP key rings cannot be null or empty");
        builder.clearVerificationKeyRings();

        assertThat(builder.build().getDecryptionKeyRings().get(0).getKeyRing()).containsExactly(1);
    }
}
