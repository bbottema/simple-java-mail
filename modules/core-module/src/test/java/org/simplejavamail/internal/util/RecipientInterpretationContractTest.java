package org.simplejavamail.internal.util;

import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.Recipient;

import static jakarta.mail.Message.RecipientType.BCC;
import static org.assertj.core.api.Assertions.assertThat;

class RecipientInterpretationContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"(comment only)", "   ", " \t "})
    void preservesSourceNameAndTypeWhenParsingFindsNoMailbox(final String source) throws Exception {
        assertThat(InternetAddress.parse(source, false)).isEmpty();

        assertThat(MiscUtil.interpretRecipient("Fixed", true, source, BCC))
                .isEqualTo(new Recipient("Fixed", source, BCC, null));
        assertThat(MiscUtil.interpretRecipient("Default", false, source, BCC))
                .isEqualTo(new Recipient("Default", source, BCC, null));
        assertThat(MiscUtil.interpretRecipient(null, false, source, BCC))
                .isEqualTo(new Recipient(null, source, BCC, null));
    }

    @Test
    void preservesTheExistingExceptionFallbackForMalformedSources() {
        final String source = "broken <address";

        assertThat(MiscUtil.interpretRecipient("Fixed", true, source, BCC))
                .isEqualTo(new Recipient("Fixed", source, BCC, null));
        assertThat(MiscUtil.interpretRecipient("Default", false, source, BCC))
                .isEqualTo(new Recipient("Default", source, BCC, null));
    }

    @Test
    void preservesPersonalNamePrecedenceAndFirstMailboxSelection() {
        final String source = "Embedded <alice@example.org>, Bob <bob@example.org>";

        assertThat(MiscUtil.interpretRecipient("Fixed", true, source, BCC))
                .isEqualTo(new Recipient("Fixed", "alice@example.org", BCC, null));
        assertThat(MiscUtil.interpretRecipient("Default", false, source, BCC))
                .isEqualTo(new Recipient("Embedded", "alice@example.org", BCC, null));
        assertThat(MiscUtil.interpretRecipient(null, true, source, BCC))
                .isEqualTo(new Recipient("Embedded", "alice@example.org", BCC, null));
        assertThat(MiscUtil.interpretRecipient("Default", false, "alice@example.org", BCC))
                .isEqualTo(new Recipient("Default", "alice@example.org", BCC, null));
    }
}
