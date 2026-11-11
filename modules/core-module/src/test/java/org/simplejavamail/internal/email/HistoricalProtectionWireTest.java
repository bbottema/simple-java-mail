package org.simplejavamail.internal.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.ProtectionInspection;
import org.simplejavamail.api.email.SignatureObservation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Old aggregate wire names remain readable without reviving a supported API or manufacturing field coverage. */
class HistoricalProtectionWireTest {
    @ParameterizedTest
    @ValueSource(strings = {"valid", "invalid", "missing", "error", "encrypted-absent", "plain"})
    void earlierSelectedOnlyAggregatesAreReadAsOpaqueDiscardableHistory(final String kind) throws Exception {
        final byte[] serialized;
        try (InputStream input = getClass().getResourceAsStream("/serialization/openpgp-details-selected-legacy-" + kind + ".base64")) {
            assertThat(input).isNotNull();
            serialized = Base64.getDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim());
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            final Object old = input.readObject();
            assertThat(old.getClass().getName()).isEqualTo("org.simplejavamail.internal.email.OpenPgpDetailsImpl");
            assertThat(old).isNotInstanceOf(ProtectionInspection.class);
            assertThat(old.getClass().getDeclaredMethods()).allMatch(java.lang.reflect.Method::isSynthetic);
            assertThat(Modifier.isPublic(old.getClass().getModifiers())).isFalse();
        }
    }

    @Test
    void oldPublicNamesAreOnlyInaccessibleWireHoldersAndCannotBeUsedAsTheNewModel() throws Exception {
        for (String type : new String[] {"org.simplejavamail.api.email.OriginalSmimeDetails",
                "org.simplejavamail.api.email.OriginalOpenPgpDetails", "org.simplejavamail.internal.email.OpenPgpSignatureResultImpl",
                "org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails"}) {
            final Class<?> wire = Class.forName(type);
            assertThat(Modifier.isPublic(wire.getModifiers())).isFalse();
            assertThat(wire.getDeclaredMethods()).allMatch(java.lang.reflect.Method::isSynthetic);
            assertThat(ProtectionInspection.class.isAssignableFrom(wire)).isFalse();
            assertThat(SignatureObservation.class.isAssignableFrom(wire)).isFalse();
        }
        assertThatThrownBy(() -> Class.forName("org.simplejavamail.api.email.OpenPgpSignatureResult"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void nonemptyHistoricalSignatureGraphsCanBeDiscardedWithoutRequiringTheOldPublicModel() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream("/serialization/openpgp-details-occurrences-legacy-nonempty.base64")) {
            assertThat(resource).isNotNull();
            final byte[] bytes = Base64.getDecoder().decode(new String(resource.readAllBytes(), StandardCharsets.US_ASCII).trim());
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                assertThat(input.readObject().getClass()).isEqualTo(Class.forName("org.simplejavamail.internal.email.OpenPgpDetailsImpl"));
            }
        }
    }
}
