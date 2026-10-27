package org.simplejavamail.recipient;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.Recipient;
import testutil.ConfigLoaderTestHelper;

import java.security.cert.X509Certificate;
import java.util.Set;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class RecipientRejectedUpdateTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @EnumSource(Shortcut.class)
    void rejectedShortcutsKeepExplicitAndInferredNamesAndAllMetadata(final Shortcut shortcut) {
        for (final String invalid : new String[] {null, ""}) {
            final X509Certificate certificate = mock(X509Certificate.class);
            final IRecipientBuilder draft = configured(certificate).withName("Chosen");
            final Recipient previous = draft.build();

            assertThatThrownBy(() -> shortcut.replace(draft, invalid)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("address is required");

            assertThat(draft.build()).isEqualTo(previous);
            assertThat(draft.withName(null).build()).isEqualTo(new Recipient("Alice", "alice@example.org", BCC, certificate,
                    Set.of(FAILURE)));
            assertThat(previous.getName()).isEqualTo("Chosen");
        }
    }

    @ParameterizedTest
    @EnumSource(Shortcut.class)
    void rejectedShortcutsKeepAnIncompleteDraftInspectableAndAllowCorrection(final Shortcut shortcut) {
        final IRecipientBuilder draft = mail.recipientBuilder().withName("Chosen").withType(BCC);

        assertThatThrownBy(() -> shortcut.replace(draft, "")).isInstanceOf(IllegalArgumentException.class);

        assertThat(draft.getName()).isEqualTo("Chosen");
        assertThat(draft.getAddress()).isNull();
        assertThat(draft.getType()).isEqualTo(BCC);
        final Recipient corrected = shortcut.replace(draft, "Bob <bob@example.org>");
        assertThat(corrected.getAddress()).isEqualTo("bob@example.org");
        assertThat(corrected.getName()).isEqualTo(shortcut.named ? "Replacement" : "Bob");
        assertThat(corrected.getType()).isEqualTo(shortcut.type);
    }

    @ParameterizedTest
    @EnumSource(TypedNameHelper.class)
    void rejectedTypedNameHelpersRetainBothNameChoicesAndAllowCorrection(final TypedNameHelper helper) {
        for (final InternetAddress invalid : new InternetAddress[] {null, new InternetAddress(), emptyAddress()}) {
            final X509Certificate certificate = mock(X509Certificate.class);
            final IRecipientBuilder draft = configured(certificate).withName("Chosen");
            final Recipient previous = draft.build();

            assertThatThrownBy(() -> helper.replace(draft, invalid)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("address is required");

            assertThat(draft.build()).isEqualTo(previous);
            assertThat(draft.withName(null).getName()).isEqualTo("Alice");
            assertThat(draft.getSmimeCertificate()).isSameAs(certificate);
            final InternetAddress corrected = emptyAddress();
            corrected.setAddress("bob@example.org");
            assertThat(helper.replace(draft, corrected).build()).isEqualTo(new Recipient("Replacement", "bob@example.org", BCC,
                    certificate, Set.of(FAILURE)));
            assertThat(previous.getName()).isEqualTo("Chosen");
        }
    }

    @ParameterizedTest
    @EnumSource(TypedNameHelper.class)
    void aTypedNameFailureIsNotHiddenAndDoesNotChangeTheAddress(final TypedNameHelper helper) {
        final IllegalArgumentException failure = new IllegalArgumentException("unreadable supplied name");
        final InternetAddress supplied = new InternetAddress() {
            @Override
            public String getPersonal() {
                throw failure;
            }
        };
        supplied.setAddress("bob@example.org");
        final IRecipientBuilder draft = configured(mock(X509Certificate.class));
        final Recipient previous = draft.build();

        assertThatThrownBy(() -> helper.replace(draft, supplied)).isSameAs(failure);

        assertThat(draft.build()).isEqualTo(previous);
    }

    @Test
    void separateAcceptedSettersRemainSeparateChanges() {
        final IRecipientBuilder draft = mail.recipientBuilder().withAddress("Alice <alice@example.org>");
        draft.withName("Bob");

        assertThatThrownBy(() -> draft.withAddress("")).isInstanceOf(IllegalArgumentException.class);

        assertThat(draft.build().getName()).isEqualTo("Bob");
        assertThat(draft.getAddress()).isEqualTo("alice@example.org");
        assertThat(draft.withName(null).getName()).isEqualTo("Alice");
    }

    @Test
    void rejectedStringNameHelpersAndAddressOnlyUpdatesKeepTheEarlierIdentity() {
        for (final String invalid : new String[] {null, ""}) {
            final IRecipientBuilder draft = configured(mock(X509Certificate.class));
            final Recipient previous = draft.build();
            assertThatThrownBy(() -> draft.withAddressAndNameOrDefault(invalid, "Replacement")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> draft.withAddressAndFixedNameOrProvided(invalid, "Replacement")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> draft.withAddressOnlyFrom(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThat(draft.build()).isEqualTo(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<>", "(comment only)", "broken <address", "Team: alice@example.org, bob@example.org;"})
    void nonemptyUninterpretableSourcesStillReplaceTheIdentityForNormalValidation(final String supplied) {
        final IRecipientBuilder draft = configured(mock(X509Certificate.class));

        assertThat(draft.withAddressAndNameOrDefault(supplied, "Default").build().getAddress()).isEqualTo(supplied);
        assertThat(draft.getName()).isEqualTo("Default");
        assertThat(draft.withAddressAndFixedNameOrProvided(supplied, "Fixed").build().getAddress()).isEqualTo(supplied);
        assertThat(draft.getName()).isEqualTo("Fixed");
    }

    private IRecipientBuilder configured(final X509Certificate certificate) {
        return mail.recipientBuilder().withAddress("Alice <alice@example.org>").withType(BCC).withSmimeCertificate(certificate)
                .withDeliveryStatusNotificationNotifyOptions(FAILURE);
    }

    private static InternetAddress emptyAddress() {
        final InternetAddress address = new InternetAddress();
        address.setAddress("");
        return address;
    }

    private enum TypedNameHelper {
        DEFAULT, FIXED;

        IRecipientBuilder replace(final IRecipientBuilder draft, final InternetAddress address) {
            return this == DEFAULT ? draft.withAddressAndNameOrDefault(address, "Replacement")
                    : draft.withAddressAndFixedNameOrProvided(address, "Replacement");
        }
    }

    private enum Shortcut {
        TO_NAMED(true, TO), CC_NAMED(true, CC), BCC_NAMED(true, BCC),
        TO_ADDRESS(false, TO), CC_ADDRESS(false, CC), BCC_ADDRESS(false, BCC);

        private final boolean named;
        private final RecipientType type;

        Shortcut(final boolean named, final RecipientType type) {
            this.named = named;
            this.type = type;
        }

        Recipient replace(final IRecipientBuilder draft, final String address) {
            if (type == TO) return named ? draft.to("Replacement", address) : draft.to(address);
            if (type == CC) return named ? draft.cc("Replacement", address) : draft.cc(address);
            return named ? draft.bcc("Replacement", address) : draft.bcc(address);
        }
    }
}
