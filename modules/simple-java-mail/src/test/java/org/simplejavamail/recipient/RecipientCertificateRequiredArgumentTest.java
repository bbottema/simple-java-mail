package org.simplejavamail.recipient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.config.ConfigLoader;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.SUCCESS;

class RecipientCertificateRequiredArgumentTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    private final X509Certificate recipientCertificate = mock(X509Certificate.class);
    private final X509Certificate groupCertificate = mock(X509Certificate.class);

    @Test
    void missingIndividualCertificateLeavesThePreviousChoiceAndBuiltRecipientsIntact() {
        final IRecipientBuilder builder = mail.recipientBuilder().withAddress("alice@example.test").withType(TO)
                .withDeliveryStatusNotificationNotifyOptions(FAILURE);
        assertThatThrownBy(() -> builder.withSmimeCertificate(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("smimeCertificate is required");
        assertThat(builder.getSmimeCertificate()).isNull();

        assertThat(builder.withSmimeCertificate(recipientCertificate)).isSameAs(builder);
        final Recipient original = builder.build();
        assertThatThrownBy(() -> builder.withSmimeCertificate(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("smimeCertificate is required");
        assertThat(builder.getSmimeCertificate()).isSameAs(recipientCertificate);
        assertThat(builder.build()).isEqualTo(original);

        assertThat(builder.clearingSmimeCertificate()).isSameAs(builder);
        assertThat(builder.build()).isEqualTo(new Recipient(null, "alice@example.test", TO, null, List.of(FAILURE)));
        assertThat(original.getSmimeCertificate()).isSameAs(recipientCertificate);
        verifyNoInteractions(recipientCertificate);
    }

    @ParameterizedTest(name = "missing {1} certificate preserves {0} policy")
    @MethodSource("groupPolicyTransitions")
    void missingGroupCertificateDoesNotChangeThePolicyOrItsSources(final String priorPolicy, final String missingPolicy) {
        final Recipient alice = new Recipient("Alice", "alice@example.test", TO, recipientCertificate, List.of(FAILURE));
        final Recipient bob = new Recipient("Bob", "bob@example.test", BCC, null);
        final List<Recipient> source = new ArrayList<>(List.of(alice, bob, alice));
        final IRecipientsBuilder group = mail.recipientsBuilder().withRecipients(source)
                .withFixedDeliveryStatusNotificationNotifyOptions(SUCCESS);
        switch (priorPolicy) {
            case "default": group.withDefaultSmimeCertificate(groupCertificate); break;
            case "fixed": group.withFixedSmimeCertificate(groupCertificate); break;
            case "clear": group.clearingSmimeCertificates(); break;
            default: break;
        }
        final Collection<Recipient> before = group.buildRecipients();
        final X509Certificate expectedAliceCertificate = priorPolicy.equals("fixed") ? groupCertificate
                : priorPolicy.equals("clear") ? null : recipientCertificate;
        final X509Certificate expectedBobCertificate = priorPolicy.equals("default") || priorPolicy.equals("fixed") ? groupCertificate : null;
        final Recipient expectedAlice = new Recipient("Alice", "alice@example.test", TO, expectedAliceCertificate, List.of(SUCCESS));
        final Recipient expectedBob = new Recipient("Bob", "bob@example.test", BCC, expectedBobCertificate, List.of(SUCCESS));
        assertThat(before).containsExactly(expectedAlice, expectedBob, expectedAlice);

        assertThatThrownBy(() -> {
            if (missingPolicy.equals("default")) {
                group.withDefaultSmimeCertificate(null);
            } else {
                group.withFixedSmimeCertificate(null);
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessage("smimeCertificate is required");

        source.clear();
        assertThat(group.buildRecipients()).containsExactlyElementsOf(before);
        assertThat(before).extracting(Recipient::getAddress).containsExactly("alice@example.test", "bob@example.test", "alice@example.test");
        assertThat(before).allSatisfy(recipient -> assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(SUCCESS));
        assertThat(alice.getSmimeCertificate()).isSameAs(recipientCertificate);
        assertThat(alice.getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);

        assertThat(group.clearingSmimeCertificates().buildRecipients()).allSatisfy(recipient -> assertThat(recipient.getSmimeCertificate()).isNull());
        assertThat(group.resetSmimeCertificatePolicy().resetDeliveryStatusNotificationNotifyPolicy().buildRecipients()).containsExactly(alice, bob, alice);
        assertThat(before).containsExactly(expectedAlice, expectedBob, expectedAlice);
        assertThatThrownBy(() -> before.clear()).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(recipientCertificate, groupCertificate);
    }

    private static Stream<Arguments> groupPolicyTransitions() {
        return Stream.of("preserve", "default", "fixed", "clear")
                .flatMap(priorPolicy -> Stream.of("default", "fixed").map(missingPolicy -> Arguments.of(priorPolicy, missingPolicy)));
    }
}
