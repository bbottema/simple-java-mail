package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectResolutionContractTest {
    @Test
    void completeSubjectPresenceAndSuppressionMatrixPreservesItsOwnPrecedence() {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final String[] values = {null, "", "alpha", "beta"};
        for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
            for (String defaults : values) {
                for (String submitted : values) {
                    for (String overrides : values) {
                        for (int flags = 0; flags < 16; flags++) {
                            final boolean ignoreDefaults = (flags & 1) != 0;
                            final boolean ignoreOverrides = (flags & 2) != 0;
                            final boolean suppressDefaultSubject = (flags & 4) != 0;
                            final boolean suppressOverrideSubject = (flags & 8) != 0;
                            final EmailTemplateOptions.Builder options = EmailTemplateOptions.builder()
                                    .ignoringDefaults(ignoreDefaults).ignoringOverrides(ignoreOverrides);
                            if (suppressDefaultSubject) { options.dontApplyDefaultValueFor(EmailProperty.SUBJECT); }
                            if (suppressOverrideSubject) { options.dontApplyOverrideValueFor(EmailProperty.SUBJECT); }
                            final Email provided = mail.emailBuilder().startingBlank().withSubject(submitted)
                                    .withTemplateOptions(options.build()).buildEmail();
                            final Email defaultTemplate = mail.emailBuilder().startingBlank().withSubject(defaults).buildEmail();
                            final Email overrideTemplate = mail.emailBuilder().startingBlank().withSubject(overrides).buildEmail();
                            final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder().load())
                                    .withConnectionConflictPolicy(policy).withEmailDefaults(defaultTemplate).withEmailOverrides(overrideTemplate);
                            final EmailGovernanceImpl governance = (EmailGovernanceImpl) builder.buildEmailGovernance();
                            final String expected = !ignoreOverrides && !suppressOverrideSubject && overrides != null ? overrides
                                    : submitted != null ? submitted : !ignoreDefaults && !suppressDefaultSubject ? defaults : null;
                            final Email result = governance.produceEmailApplyingDefaultsAndOverrides(provided);
                            assertThat(result.getSubject()).as("policy=%s flags=%s", policy, flags).isEqualTo(expected);
                            assertThat(governance.produceEmailApplyingDefaultsAndOverrides(result).getSubject()).isEqualTo(expected);
                            assertThat(mail.emailBuilder().copying(result).buildEmail().getSubject()).isEqualTo(expected);
                            assertThat(provided.getSubject()).isEqualTo(submitted);
                        }
                    }
                }
            }
        }
    }

    @Test
    void replacingDefaultsDoesNotFillItsMissingSubjectFromProperties() {
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.defaults.subject", "configured")).load();
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(config);
        final Email replacement = mail.emailBuilder().startingBlank().buildEmail();
        final EmailGovernanceImpl governance = (EmailGovernanceImpl) new MailerRegularBuilderImpl(config)
                .withEmailDefaults(replacement).buildEmailGovernance();
        assertThat(governance.produceEmailApplyingDefaultsAndOverrides(null).getSubject()).isNull();
    }
}
