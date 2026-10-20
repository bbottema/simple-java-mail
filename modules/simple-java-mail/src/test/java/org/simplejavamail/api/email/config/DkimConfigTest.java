package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.internal.dkimsupport.DKIMSigner;
import jakarta.mail.internet.MimeMessage;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class DkimConfigTest {

    @Test
    public void invalidDkimChoicesFailBeforeReadingOrSerializingMessageContent() {
        final Email email = mock(Email.class);
        final MimeMessage message = mock(MimeMessage.class);
        final Recipient sender = mock(Recipient.class);
        final DkimConfig config = completeConfig().excludedHeadersFromDkimDefaultSigningList("From").build();

        assertThatThrownBy(() -> new DKIMSigner().signMessageWithDKIM(email, message, config, sender))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must include the From header");
        verifyNoInteractions(email, message, sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "FrOm", " FROM "})
    public void dkimModuleRejectsFromExclusionsWhenSigning(final String header) {
        final DkimConfig config = completeConfig().excludedHeadersFromDkimDefaultSigningList(header).build();
        final Email email = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                .from("sender@example.com").withPlainText("body").signWithDomainKey(config).buildEmail();

        assertThat(config.getExcludedHeadersFromDkimDefaultSigningList()).containsExactly(header);
        assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
                .hasStackTraceContaining("DKIM signatures must include the From header");
    }

    @Test
    public void convenienceBuildersCaptureChoicesWithoutApplyingDkimRules() {
        final Email email = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                .signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData("key".getBytes()).dkimSigningDomain("example.com").dkimSelector("selector").excludedHeadersFromDkimDefaultSigningList(Collections.singleton(" FROM ")).build()).buildEmail();
        assertThat(email.getDkimConfig().getExcludedHeadersFromDkimDefaultSigningList()).containsExactly(" FROM ");
    }

    @Test
    public void retainsValidHeaderExclusionsAsImmutableConfiguration() {
        final DkimConfig config = completeConfig()
                .excludedHeadersFromDkimDefaultSigningList("Message-ID", "Date")
                .build();

        assertThat(config.getExcludedHeadersFromDkimDefaultSigningList()).containsExactlyInAnyOrder("Message-ID", "Date");
        assertThatThrownBy(() -> config.getExcludedHeadersFromDkimDefaultSigningList().add("From"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private DkimConfig.DkimConfigBuilder completeConfig() {
        return DkimConfig.builder()
                .dkimPrivateKeyData("key")
                .dkimSigningDomain("example.com")
                .dkimSelector("selector");
    }
}
