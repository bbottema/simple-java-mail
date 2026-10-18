package org.simplejavamail.recipient;

import org.simplejavamail.api.SimpleJavaMail;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.Recipient;

import java.security.cert.X509Certificate;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

public class RecipientBuilderTest {

    @Test
    public void toCreatesNamedToRecipient() {
        assertThat(SimpleJavaMail.fromDefaults().recipientBuilder().to("Alice", "alice@example.com"))
                .isEqualTo(new Recipient("Alice", "alice@example.com", TO, null));
    }

    @Test
    public void ccCreatesNamedCcRecipient() {
        assertThat(SimpleJavaMail.fromDefaults().recipientBuilder().cc("Audit", "audit@example.com"))
                .isEqualTo(new Recipient("Audit", "audit@example.com", CC, null));
    }

    @Test
    public void bccCreatesNamedBccRecipient() {
        assertThat(SimpleJavaMail.fromDefaults().recipientBuilder().bcc("Archive", "archive@example.com"))
                .isEqualTo(new Recipient("Archive", "archive@example.com", BCC, null));
    }

    @Test
    public void convenienceFactoriesUseRecipientBuilderAddressValidation() {
        final IRecipientBuilder builder = SimpleJavaMail.fromDefaults().recipientBuilder();
        assertThatThrownBy(() -> builder.to("Alice", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("address is required");
        assertThatThrownBy(() -> builder.cc("Audit", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("address is required");
        assertThatThrownBy(() -> builder.bcc("Archive", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("address is required");
    }

    @Test
    public void shortcutsReplaceRecipientIdentityAndRetainConfiguredMetadata() {
        final X509Certificate certificate = mock(X509Certificate.class);
        final IRecipientBuilder builder = SimpleJavaMail.fromDefaults().recipientBuilder()
                .withName("Original")
                .withAddress("original@example.com")
                .withType(BCC)
                .withSmimeCertificate(certificate)
                .withDeliveryStatusNotificationNotifyOptions(FAILURE);

        final Recipient to = builder.to("Alice", "alice@example.com");
        final Recipient cc = builder.cc("Audit", "audit@example.com");
        final Recipient bcc = builder.bcc("Archive", "archive@example.com");

        assertThat(to).isEqualTo(new Recipient("Alice", "alice@example.com", TO, certificate, singleton(FAILURE)));
        assertThat(cc).isEqualTo(new Recipient("Audit", "audit@example.com", CC, certificate, singleton(FAILURE)));
        assertThat(bcc).isEqualTo(new Recipient("Archive", "archive@example.com", BCC, certificate, singleton(FAILURE)));
        assertThat(builder.build()).isEqualTo(bcc);
    }

    @Test
    public void nullNamesClearPreviouslyConfiguredNames() {
        final IRecipientBuilder builder = SimpleJavaMail.fromDefaults().recipientBuilder();
        assertThat(builder.withName("Previous").to(null, "alice@example.com"))
                .isEqualTo(new Recipient(null, "alice@example.com", TO, null));
        assertThat(builder.withName("Previous").cc(null, "audit@example.com"))
                .isEqualTo(new Recipient(null, "audit@example.com", CC, null));
        assertThat(builder.withName("Previous").bcc(null, "archive@example.com"))
                .isEqualTo(new Recipient(null, "archive@example.com", BCC, null));
    }

    @Test
    public void shortcutsDoNotSplitAddressLists() {
        final IRecipientBuilder builder = SimpleJavaMail.fromDefaults().recipientBuilder();
        final String unsplitAddresses = "alice@example.com;bob@example.com";
        assertThat(builder.to("Team", unsplitAddresses).getAddress()).isEqualTo(unsplitAddresses);
        assertThat(builder.cc("Team", unsplitAddresses).getAddress()).isEqualTo(unsplitAddresses);
        assertThat(builder.bcc("Team", unsplitAddresses).getAddress()).isEqualTo(unsplitAddresses);
    }

    @Test
    public void shortcutsExposeOnlyTheNamedSingleAddressSignatures() {
        assertThat(IRecipientBuilder.class.getDeclaredMethods())
                .filteredOn(method -> asList("to", "cc", "bcc").contains(method.getName()))
                .hasSize(3)
                .allSatisfy(method -> {
                    assertThat(method.getParameterTypes()).containsExactly(String.class, String.class);
                    assertThat(method.getReturnType()).isEqualTo(Recipient.class);
                });
    }
}
