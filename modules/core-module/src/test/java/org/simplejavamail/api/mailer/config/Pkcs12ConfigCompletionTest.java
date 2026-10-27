package org.simplejavamail.api.mailer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Pkcs12ConfigCompletionTest {

    @ParameterizedTest
    @EnumSource(RequiredChoice.class)
    void rejectsEachMissingChoiceAtConstruction(final RequiredChoice missing) {
        final Pkcs12Config.Pkcs12ConfigBuilder builder = Pkcs12Config.builder();
        if (missing != RequiredChoice.STORE) builder.pkcs12Store(new byte[] {1});
        if (missing != RequiredChoice.STORE_PASSWORD) builder.storePassword("store-secret");
        if (missing != RequiredChoice.ALIAS) builder.keyAlias("mail");
        if (missing != RequiredChoice.KEY_PASSWORD) builder.keyPassword("key-secret");

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(missing.field).hasMessageNotContaining("store-secret")
                .hasMessageNotContaining("key-secret").hasMessageNotContaining("[1]");
    }

    @Test
    void incompleteInspectionAndCorrectionRemainPassive() {
        final Pkcs12Config.Pkcs12ConfigBuilder builder = Pkcs12Config.builder();
        assertThat(builder.getPkcs12StoreData()).isNull();
        assertThat(builder.getStorePassword()).isNull();
        assertThat(builder.getKeyAlias()).isNull();
        assertThat(builder.getKeyPassword()).isNull();
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);

        builder.pkcs12Store(new byte[] {1});
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("storePassword");
        assertThat(builder.getPkcs12StoreData()).containsExactly((byte) 1);
        assertThat(builder.getStorePassword()).isNull();

        final Pkcs12Config completed = builder.storePassword("").keyAlias("mail").keyPassword("").build();
        assertThat(completed.getStorePassword()).isEmpty();
        assertThat(completed.getKeyPassword()).isEmpty();
    }

    @Test
    void explicitEmptyArraysRemainChoicesWithoutCryptographicInterpretation() {
        final Pkcs12Config completed = Pkcs12Config.builder().pkcs12Store(new byte[0])
                .storePassword(new char[0]).keyAlias("").keyPassword(new char[0]).build();
        assertThat(completed.getPkcs12StoreData()).isEmpty();
        assertThat(completed.getStorePassword()).isEmpty();
        assertThat(completed.getKeyAlias()).isEmpty();
        assertThat(completed.getKeyPassword()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(NullInput.class)
    void rejectsNullBeforeReplacingAStoredChoice(final NullInput input) {
        final Pkcs12Config.Pkcs12ConfigBuilder builder = completeBuilder();
        final Pkcs12Config original = builder.build();

        assertThatThrownBy(() -> input.apply(builder)).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.build()).isEqualTo(original);
    }

    @Test
    void replacementAndInspectionKeepCompletedMaterialDetached() {
        final byte[] bytes = {1, 2};
        final char[] storePassword = {'s'};
        final char[] keyPassword = {'k'};
        final Pkcs12Config.Pkcs12ConfigBuilder builder = Pkcs12Config.builder().pkcs12Store(bytes)
                .storePassword(storePassword).keyAlias("mail").keyPassword(keyPassword);
        final Pkcs12Config original = builder.build();
        bytes[0] = 9;
        storePassword[0] = 'x';
        keyPassword[0] = 'x';
        builder.getPkcs12StoreData()[0] = 9;
        builder.getStorePassword()[0] = 'x';
        builder.getKeyPassword()[0] = 'x';
        original.getPkcs12StoreData()[0] = 9;
        original.getStorePassword()[0] = 'x';
        original.getKeyPassword()[0] = 'x';

        final Pkcs12Config replacement = builder.pkcs12Store(new byte[] {3}).storePassword("next-store")
                .keyAlias("next-mail").keyPassword("next-key").build();
        assertThat(original.getPkcs12StoreData()).containsExactly((byte) 1, (byte) 2);
        assertThat(original.getStorePassword()).containsExactly('s');
        assertThat(original.getKeyPassword()).containsExactly('k');
        assertThat(original.getKeyAlias()).isEqualTo("mail");
        assertThat(replacement.getPkcs12StoreData()).containsExactly((byte) 3);
        assertThat(replacement.getStorePassword()).containsExactly("next-store".toCharArray());
        assertThat(replacement.getKeyPassword()).containsExactly("next-key".toCharArray());
        assertThat(replacement.getKeyAlias()).isEqualTo("next-mail");
    }

    @Test
    void supportedSerializationRetainsExplicitEmptyPasswords() throws Exception {
        final Pkcs12Config original = completeBuilder().storePassword("").keyPassword("").build();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        final Pkcs12Config restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Pkcs12Config) input.readObject();
        }
        assertThat(restored).isEqualTo(original);
        assertThat(restored.getStorePassword()).isEmpty();
        assertThat(restored.getKeyPassword()).isEmpty();
        assertThat(restored.toString()).doesNotContain("store-secret", "key-secret");
    }

    private static Pkcs12Config.Pkcs12ConfigBuilder completeBuilder() {
        return Pkcs12Config.builder().pkcs12Store(new byte[] {1}).storePassword("store-secret")
                .keyAlias("mail").keyPassword("key-secret");
    }

    private enum RequiredChoice {
        STORE("pkcs12StoreData"), STORE_PASSWORD("storePassword"), ALIAS("keyAlias"), KEY_PASSWORD("keyPassword");

        private final String field;

        RequiredChoice(final String field) {
            this.field = field;
        }
    }

    private enum NullInput {
        BYTES, PATH, FILE, STRING_PATH, STREAM, STORE_PASSWORD_TEXT, STORE_PASSWORD_ARRAY, ALIAS, KEY_PASSWORD_TEXT, KEY_PASSWORD_ARRAY;

        private void apply(final Pkcs12Config.Pkcs12ConfigBuilder builder) {
            switch (this) {
                case BYTES: builder.pkcs12Store((byte[]) null); break;
                case PATH: builder.pkcs12Store((Path) null); break;
                case FILE: builder.pkcs12Store((File) null); break;
                case STRING_PATH: builder.pkcs12Store((String) null); break;
                case STREAM: builder.pkcs12Store((InputStream) null); break;
                case STORE_PASSWORD_TEXT: builder.storePassword((String) null); break;
                case STORE_PASSWORD_ARRAY: builder.storePassword((char[]) null); break;
                case ALIAS: builder.keyAlias(null); break;
                case KEY_PASSWORD_TEXT: builder.keyPassword((String) null); break;
                case KEY_PASSWORD_ARRAY: builder.keyPassword((char[]) null); break;
                default: throw new IllegalArgumentException("Unknown input: " + this);
            }
        }
    }
}
