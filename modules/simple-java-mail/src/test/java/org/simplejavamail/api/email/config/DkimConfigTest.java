package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DkimConfigTest {

    @Test
    public void rejectsFromExclusionsCaseInsensitively() {
        assertThatThrownBy(() -> completeConfig()
                .excludedHeadersFromDkimDefaultSigningList("from")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DKIM signatures must include the From header; it cannot be excluded from the default signing list");

        final Set<String> exclusions = new HashSet<>(Arrays.asList("Message-ID", "FrOm"));
        assertThatThrownBy(() -> completeConfig()
                .excludedHeadersFromDkimDefaultSigningList(exclusions)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must include the From header");
    }

    @Test
    public void rejectsFromExclusionsFromConvenienceBuilders() {
        assertThatThrownBy(() -> EmailBuilder.startingBlank()
                .signWithDomainKey("key".getBytes(), "example.com", "selector", Collections.singleton(" FROM ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must include the From header");

        assertThatThrownBy(() -> MailerBuilder
                .withSMTPServer("host", 25, null, null)
                .withDefaultDkimSigning("key".getBytes(), "example.com", "selector", Collections.singleton("fRoM")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must include the From header");
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
