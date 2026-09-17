package org.simplejavamail.recipient;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.*;

class RecipientDsnTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void recipientSettingsReplaceRatherThanMergeAndBuiltSnapshotsStayImmutable() {
        final NotifyOption[] source = {FAILURE, DELAY};
        final IRecipientBuilder builder = new RecipientBuilder().withAddress("same@example.test").withType(TO)
                .withDeliveryStatusNotificationNotifyOptions(source);
        source[0] = SUCCESS;
        final Recipient first = builder.build();
        builder.withDeliveryStatusNotificationNotifyOptions(NEVER);
        assertThat(first.getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE, DELAY);
        assertThat(builder.build().getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER);
        assertThatThrownBy(() -> first.getDeliveryStatusNotificationNotifyOptions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(builder.clearingDeliveryStatusNotificationNotifyOptions().build().getDeliveryStatusNotificationNotifyOptions()).isEmpty();
    }

    @Test
    void rejectsInvalidOptionsWithoutChangingThePreviousPolicy() {
        final IRecipientBuilder builder = new RecipientBuilder().withAddress("a@example.test").withDeliveryStatusNotificationNotifyOptions(SUCCESS);
        assertThatThrownBy(() -> builder.withDeliveryStatusNotificationNotifyOptions(NEVER, FAILURE)).hasMessageContaining("NEVER");
        assertThatThrownBy(() -> builder.withDeliveryStatusNotificationNotifyOptions(FAILURE, null)).hasMessageContaining("null");
        assertThatThrownBy(() -> builder.withDeliveryStatusNotificationNotifyOptions((NotifyOption[]) null)).isInstanceOf(NullPointerException.class);
        assertThat(builder.getDeliveryStatusNotificationNotifyOptions()).containsExactly(SUCCESS);
        assertThat(builder.withDeliveryStatusNotificationNotifyOptions(new NotifyOption[0]).getDeliveryStatusNotificationNotifyOptions()).isEmpty();
    }

    @Test
    void groupDefaultFixedAndClearAreIndependentOfCertificatePolicy() {
        final X509Certificate certificate = mock(X509Certificate.class);
        final Recipient explicit = recipient(NEVER);
        final Recipient inherited = recipient();
        final IRecipientsBuilder group = new RecipientsBuilder().withRecipients(List.of(explicit, inherited), BCC)
                .withFixedSmimeCertificate(certificate).withDefaultDeliveryStatusNotificationNotifyOptions(FAILURE, DELAY);
        final List<Recipient> defaulted = new ArrayList<>(group.buildRecipients());
        assertThat(defaulted.get(0).getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER);
        assertThat(defaulted.get(1).getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE, DELAY);
        assertThat(defaulted).allSatisfy(recipient -> {
            assertThat(recipient.getType()).isEqualTo(BCC);
            assertThat(recipient.getSmimeCertificate()).isSameAs(certificate);
        });
        assertThat(group.withFixedDeliveryStatusNotificationNotifyOptions(SUCCESS).buildRecipients())
                .allSatisfy(recipient -> assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(SUCCESS));
        assertThat(group.clearingDeliveryStatusNotificationNotifyOptions().buildRecipients()).allSatisfy(recipient -> {
            assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).isEmpty();
            assertThat(recipient.getSmimeCertificate()).isSameAs(certificate);
        });
        assertThat(group.withDefaultDeliveryStatusNotificationNotifyOptions(DELAY).buildRecipients()).first()
                .satisfies(recipient -> assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER));
        assertThat(explicit.getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER);
        assertThat(inherited.getDeliveryStatusNotificationNotifyOptions()).isEmpty();
    }

    @Test
    void allGroupSettersValidateAndTheLastPolicyWins() {
        final IRecipientsBuilder group = new RecipientsBuilder().withRecipient(recipient(NEVER))
                .withDefaultDeliveryStatusNotificationNotifyOptions(FAILURE).withFixedDeliveryStatusNotificationNotifyOptions(SUCCESS);
        assertThat(group.buildRecipients()).singleElement().satisfies(recipient ->
                assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(SUCCESS));
        assertThatThrownBy(() -> group.withFixedDeliveryStatusNotificationNotifyOptions(NEVER, SUCCESS)).hasMessageContaining("NEVER");
        assertThatThrownBy(() -> group.withDefaultDeliveryStatusNotificationNotifyOptions(NEVER, FAILURE)).hasMessageContaining("NEVER");
        assertThat(group.withFixedDeliveryStatusNotificationNotifyOptions(new NotifyOption[0]).buildRecipients()).singleElement()
                .satisfies(recipient -> assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).isEmpty());
    }

    @Test
    void emailCopiesGovernanceAndOverridesRetainRecipientValuesAndDuplicateOccurrences() {
        final Email defaults = mail.emailBuilder().startingBlank().withDeliveryStatusNotificationNotifyOptions(FAILURE).buildEmail();
        final Email overrides = mail.emailBuilder().startingBlank().withDeliveryStatusNotificationNotifyOptions(DELAY).buildEmail();
        final Email original = mail.emailBuilder().startingBlank().withRecipients(recipient(NEVER), recipient(SUCCESS), recipient()).buildEmail();
        final Email resolved = mail.emailBuilder().copying(original)
                .buildEmailCompletedWithDefaultsAndOverrides(new EmailGovernanceImpl(null, defaults, overrides, null));
        assertThat(resolved.getDeliveryStatusNotification().getNotifyOptions()).containsExactly(DELAY);
        assertThat(resolved.getRecipients()).containsExactlyElementsOf(original.getRecipients());
        assertThat(resolved.getRecipients()).hasSize(3);
        assertThat(original.getDeliveryStatusNotification()).isNull();
        assertThat(mail.emailBuilder().copying(original).withOverrideReceivers(recipient(NEVER)).buildEmail().getOverrideReceivers())
                .singleElement().satisfies(recipient -> assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER));
    }

    @Test
    void serializationRetainsPreferencesAndRestoresFallbackForOlderRecipients() throws Exception {
        assertThat(roundTrip(recipient(FAILURE, DELAY))).isEqualTo(recipient(FAILURE, DELAY));
        final Recipient legacy = recipient();
        final Field field = Recipient.class.getDeclaredField("deliveryStatusNotificationNotifyOptions");
        field.setAccessible(true);
        field.set(legacy, null); // The value Java serialization supplies when an older stream has no such field.
        assertThat(roundTrip(legacy).getDeliveryStatusNotificationNotifyOptions()).isEmpty();
        final List<NotifyOption> options = new ArrayList<>(List.of(NEVER));
        final Recipient recipient = new Recipient(null, "same@example.test", TO, null, options);
        options.clear();
        assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER);
    }

    @Test
    void recipientBuildersOfferOnlyTypedNotificationChoices() throws Exception {
        for (final Class<?> builderType : List.of(IRecipientBuilder.class, RecipientBuilder.class)) {
            assertThat(builderType.getMethod("withDeliveryStatusNotificationNotifyOptions", NotifyOption[].class)).isNotNull();
            assertThatThrownBy(() -> builderType.getMethod("withDeliveryStatusNotificationNotifyOptions", String.class))
                    .isInstanceOf(NoSuchMethodException.class);
        }
        for (final Class<?> builderType : List.of(IRecipientsBuilder.class, RecipientsBuilder.class)) {
            for (final String methodName : List.of("withDefaultDeliveryStatusNotificationNotifyOptions", "withFixedDeliveryStatusNotificationNotifyOptions")) {
                assertThat(builderType.getMethod(methodName, NotifyOption[].class)).isNotNull();
                assertThatThrownBy(() -> builderType.getMethod(methodName, String.class)).isInstanceOf(NoSuchMethodException.class);
            }
        }
    }

    private static Recipient roundTrip(final Recipient recipient) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(recipient);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Recipient) input.readObject();
        }
    }

    private static Recipient recipient(final NotifyOption... options) {
        return new RecipientBuilder().withAddress("same@example.test").withType(TO).withDeliveryStatusNotificationNotifyOptions(options).build();
    }
}
