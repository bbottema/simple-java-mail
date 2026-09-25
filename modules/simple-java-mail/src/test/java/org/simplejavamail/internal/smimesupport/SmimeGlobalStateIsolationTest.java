package org.simplejavamail.internal.smimesupport;

import jakarta.activation.CommandMap;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.util.CertificationUtil;

import java.io.File;
import java.security.Provider;
import java.security.Security;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeGlobalStateIsolationTest {

    @Test
    void configuringSigningAndReadingSmimeDoesNotChangeJvmProvidersOrTheApplicationCommandMap() throws Exception {
        final Email source = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                .ignoringDefaults(true)
                .from("sender@example.com")
                .withRecipients(new Recipient(null, "receiver@example.com", RecipientType.TO, null))
                .withSubject("S/MIME isolation")
                .withPlainText("signed body")
                .signWithSmime(SmimeSigningConfig.builder()
                        .pkcs12Config(loadPkcs12KeyStore())
                        .build())
                .buildEmail();
        final Provider[] providersBefore = Security.getProviders();
        final CommandMap commandMapBefore = CommandMap.getDefaultCommandMap();

        CertificationUtil.readFromPem(new File(determineResourceFolder("simple-java-mail")
                + "/test/resources/pkcs12/smime_test_user.pem.standard.crt"));
        final MimeMessage signed = EmailConverter.emailToMimeMessage(source);
        final Email parsed = EmailConverter.mimeMessageToEmail(signed);

        assertThat(parsed.getOriginalSmimeDetails().getVerificationStatus())
                .isEqualTo(org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(Security.getProviders()).containsExactly(providersBefore);
        assertThat(CommandMap.getDefaultCommandMap()).isSameAs(commandMapBefore);
    }
}
