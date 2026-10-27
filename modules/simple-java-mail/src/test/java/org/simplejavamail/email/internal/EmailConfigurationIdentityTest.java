package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationResolution;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import static org.assertj.core.api.Assertions.assertThat;

class EmailConfigurationIdentityTest {
    @Test
    void subjectAndBodyKeepSeparateLogicalTargetsDespiteSharingTheirJavaType() {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final Email defaults = mail.emailBuilder().startingBlank().withSubject("default subject").withPlainText("default body").buildEmail();
        final Email submitted = mail.emailBuilder().startingBlank().withSubject("submitted subject").withPlainText("submitted body").buildEmail();
        final Email overrides = mail.emailBuilder().startingBlank().withSubject("").withPlainText("override body").buildEmail();

        final ConfigurationResolution<String> subject = EmailConfigurationResolver.scalar(
                submitted, defaults, overrides, EmailProperty.SUBJECT, Email::getSubject);
        final ConfigurationResolution<String> body = EmailConfigurationResolver.scalar(
                submitted, defaults, overrides, EmailProperty.BODY_TEXT, Email::getPlainText);

        assertThat(subject.getSetting()).isEqualTo(ConfigurationSetting.of("email", "SUBJECT")).isNotEqualTo(body.getSetting());
        assertThat(body.getSetting()).isEqualTo(ConfigurationSetting.of("email", "BODY_TEXT"));
        assertThat(subject.getDeclarations()).extracting(ConfigurationDeclaration::getSetting).containsOnly(subject.getSetting());
        assertThat(body.getDeclarations()).extracting(ConfigurationDeclaration::getSetting).containsOnly(body.getSetting());
        assertThat(subject.valueOrNull()).isEmpty();
        assertThat(body.valueOrNull()).isEqualTo("override body");
    }
}
