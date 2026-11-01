package org.simplejavamail.api.email;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.config.DkimConfig;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DkimSnapshotRestorationTest {

    @Test
    void legacyExclusionsAreImmutableWithoutChangingTheirEntriesOrOtherPreferences() throws Exception {
        final DkimConfig restored = (DkimConfig) legacyGraph()[0];

        assertPreferences(restored);
        assertThat(restored.getExcludedHeadersFromDkimDefaultSigningList())
                .containsExactlyInAnyOrder("Subject", " Date ", null, "From", "");
        assertThatThrownBy(() -> restored.getExcludedHeadersFromDkimDefaultSigningList().add("Message-ID"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legacyExternalSetAliasCannotChangeRestoredConfigurationOrEmailsUsingIt() throws Exception {
        final Object[] graph = legacyGraph();
        final DkimConfig restored = (DkimConfig) graph[0];
        @SuppressWarnings("unchecked")
        final Set<String> alias = (Set<String>) graph[1];
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        final Email original = mail.emailBuilder().startingBlank().signWithDomainKey(restored).buildEmail();
        final Email copy = mail.emailBuilder().copying(original).buildEmail();

        alias.clear();
        alias.add("External replacement");

        assertThat(original.getDkimConfig().getExcludedHeadersFromDkimDefaultSigningList())
                .containsExactlyInAnyOrder("Subject", " Date ", null, "From", "");
        assertThat(copy.getDkimConfig()).isEqualTo(restored);
        assertPreferences(copy.getDkimConfig());
    }

    @Test
    void legacyNullAndEmptyChoicesRemainDistinct() throws Exception {
        final Object[] graph = legacyGraph();
        final DkimConfig empty = (DkimConfig) graph[2];
        final DkimConfig absent = (DkimConfig) graph[3];

        assertThat(empty.getExcludedHeadersFromDkimDefaultSigningList()).isEmpty();
        assertThatThrownBy(() -> empty.getExcludedHeadersFromDkimDefaultSigningList().add("Subject"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(absent.getExcludedHeadersFromDkimDefaultSigningList()).isNull();
        assertPreferences(empty);
        assertPreferences(absent);
    }

    @Test
    void restoredEmailRoundTripKeepsImmutableSigningChoices() throws Exception {
        final DkimConfig restored = (DkimConfig) legacyGraph()[0];
        final Email original = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.org").to("receiver@example.org").withPlainText("body")
                .signWithDomainKey(restored).buildEmail();
        final Email roundTrip = (Email) roundTrip(original);

        assertThat(roundTrip.getDkimConfig()).isEqualTo(restored);
        assertThatThrownBy(() -> roundTrip.getDkimConfig().getExcludedHeadersFromDkimDefaultSigningList().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertPreferences(roundTrip.getDkimConfig());
    }

    @Test
    void contemporarySnapshotsRetainAbsentEmptyAndSuppliedChoices() throws Exception {
        for (Set<String> exclusions : Arrays.asList(null, new HashSet<String>(), new HashSet<>(Arrays.asList("Date", null)))) {
            final DkimConfig original = DkimConfig.builder().dkimPrivateKeyData(new byte[]{8, 9})
                    .dkimSigningDomain("current.example.org").dkimSelector("current-selector")
                    .excludedHeadersFromDkimDefaultSigningList(exclusions).build();
            final DkimConfig restored = (DkimConfig) roundTrip(original);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.getExcludedHeadersFromDkimDefaultSigningList()).isEqualTo(exclusions);
            if (exclusions != null) {
                assertThatThrownBy(() -> restored.getExcludedHeadersFromDkimDefaultSigningList().add("Subject"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
            assertThat(restored.getUseLengthParam()).isNull();
            assertThat(restored.getHeaderCanonicalization()).isNull();
            assertThat(restored.getBodyCanonicalization()).isNull();
            assertThat(restored.getSigningAlgorithm()).isNull();
        }
    }

    private static Object[] legacyGraph() throws Exception {
        final byte[] bytes;
        try (InputStream input = requireNonNull(DkimSnapshotRestorationTest.class
                .getResourceAsStream("/serialization/dkim-config-9.1.8.base64"), "9.1.8 DKIM fixture")) {
            bytes = Base64.getDecoder().decode(new String(input.readAllBytes(), US_ASCII).trim());
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (Object[]) input.readObject();
        }
    }

    private static void assertPreferences(final DkimConfig configuration) {
        assertThat(configuration.getDkimPrivateKeyData()).containsExactly(1, 2, 3);
        assertThat(configuration.getDkimSigningDomain()).isEqualTo("legacy.example.org");
        assertThat(configuration.getDkimSelector()).isEqualTo("legacy-selector");
        assertThat(configuration.getUseLengthParam()).isTrue();
        assertThat(configuration.getHeaderCanonicalization()).isEqualTo(DkimConfig.Canonicalization.SIMPLE);
        assertThat(configuration.getBodyCanonicalization()).isEqualTo(DkimConfig.Canonicalization.RELAXED);
        assertThat(configuration.getSigningAlgorithm()).isEqualTo("legacy-algorithm");
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
