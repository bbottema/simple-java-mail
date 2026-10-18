package org.simplejavamail.recipient;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.config.ConfigLoader;

import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.NEVER;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.SUCCESS;

class RecipientGroupPolicyResetTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    private final X509Certificate aliceCertificate = mock(X509Certificate.class);
    private final X509Certificate companyCertificate = mock(X509Certificate.class);
    private final Recipient alice = new Recipient("Alice", "alice@example.test", TO, aliceCertificate, List.of(FAILURE));
    private final Recipient bob = new Recipient("Bob", "bob@example.test", BCC, null, List.of(NEVER));

    @Test
    void certificateResetRestoresOriginalMetadataWithoutRemovingTheNotifyPolicy() {
        final IRecipientsBuilder group = mail.recipientsBuilder().withRecipients(alice, bob)
                .withFixedSmimeCertificate(companyCertificate).withFixedDeliveryStatusNotificationNotifyOptions(SUCCESS);
        final Collection<Recipient> fixed = group.buildRecipients();
        assertThat(group.resetSmimeCertificatePolicy()).isSameAs(group);
        assertThat(group.buildRecipients()).containsExactly(
                new Recipient("Alice", "alice@example.test", TO, aliceCertificate, List.of(SUCCESS)),
                new Recipient("Bob", "bob@example.test", BCC, null, List.of(SUCCESS)));
        assertThat(fixed).allSatisfy(recipient -> assertThat(recipient.getSmimeCertificate()).isSameAs(companyCertificate));
        assertThat(alice.getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);
    }

    @Test
    void notifyResetRestoresOriginalPreferencesWithoutRemovingTheCertificatePolicy() {
        final IRecipientsBuilder group = mail.recipientsBuilder().withRecipients(alice, bob)
                .withDefaultSmimeCertificate(companyCertificate).withFixedDeliveryStatusNotificationNotifyOptions(SUCCESS);
        final Collection<Recipient> fixed = group.buildRecipients();
        assertThat(group.resetDeliveryStatusNotificationNotifyPolicy()).isSameAs(group);
        assertThat(group.buildRecipients()).containsExactly(alice,
                new Recipient("Bob", "bob@example.test", BCC, companyCertificate, List.of(NEVER)));
        assertThat(fixed).allSatisfy(recipient -> assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(SUCCESS));
    }

    @Test
    void resetsAlsoUndoClearAndDefaultPoliciesRatherThanStrippingOriginalMetadata() {
        final IRecipientsBuilder group = mail.recipientsBuilder().withRecipients(alice, bob);
        final Collection<Recipient> cleared = group.clearingSmimeCertificates().clearingDeliveryStatusNotificationNotifyOptions().buildRecipients();
        assertThat(cleared).allSatisfy(recipient -> {
            assertThat(recipient.getSmimeCertificate()).isNull();
            assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).isEmpty();
        });
        assertThat(group.resetSmimeCertificatePolicy().resetDeliveryStatusNotificationNotifyPolicy().buildRecipients()).containsExactly(alice, bob);
        group.withDefaultSmimeCertificate(companyCertificate).withDefaultDeliveryStatusNotificationNotifyOptions(SUCCESS);
        assertThat(group.resetSmimeCertificatePolicy().resetDeliveryStatusNotificationNotifyPolicy().buildRecipients()).containsExactly(alice, bob);
        assertThat(cleared).allSatisfy(recipient -> assertThat(recipient.getSmimeCertificate()).isNull());
    }

    @Test
    void repeatedResetsLeaveNewAndPreviouslyConfiguredGroupsInTheSamePreservingState() {
        final IRecipientsBuilder group = mail.recipientsBuilder().resetSmimeCertificatePolicy()
                .resetDeliveryStatusNotificationNotifyPolicy().withRecipients(alice, bob);
        assertThat(group.resetSmimeCertificatePolicy().resetDeliveryStatusNotificationNotifyPolicy().buildRecipients()).containsExactly(alice, bob);
        group.withFixedSmimeCertificate(companyCertificate).withFixedDeliveryStatusNotificationNotifyOptions(SUCCESS);
        group.resetSmimeCertificatePolicy().resetDeliveryStatusNotificationNotifyPolicy();
        group.withFixedDeliveryStatusNotificationNotifyOptions(SUCCESS);
        assertThat(group.buildRecipients()).allSatisfy(recipient ->
                assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(SUCCESS));
        assertThat(group.resetDeliveryStatusNotificationNotifyPolicy().buildRecipients()).containsExactly(alice, bob);
    }
}
