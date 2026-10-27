package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectResolutionContractTest {
    private enum Suppression {
        NONE, GLOBAL_DEFAULTS, DEFAULT_SUBJECT, GLOBAL_OVERRIDES, OVERRIDE_SUBJECT,
        BOTH_GLOBAL, BOTH_SUBJECT, DEFAULTS_TWICE, OVERRIDES_TWICE, MIXED_DEFAULT_GLOBAL, MIXED_OVERRIDE_GLOBAL
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjectRules")
    void subjectPresenceAndSuppressionRulesPreserveTheirOwnPrecedence(final String scenario, final String defaults,
            final String submitted, final String overrides, final Suppression suppression,
            final ConnectionConflictPolicy policy, final String expected) {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final Email provided = mail.emailBuilder().startingBlank().withSubject(submitted)
                .withTemplateOptions(options(suppression)).buildEmail();
        final Email defaultTemplate = mail.emailBuilder().startingBlank().withSubject(defaults).buildEmail();
        final Email overrideTemplate = mail.emailBuilder().startingBlank().withSubject(overrides).buildEmail();
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder().load())
                .withConnectionConflictPolicy(policy).withEmailDefaults(defaultTemplate).withEmailOverrides(overrideTemplate);
        final EmailGovernanceImpl governance = (EmailGovernanceImpl) builder.buildEmailGovernance();
        final Email result = governance.produceEmailApplyingDefaultsAndOverrides(provided);
        assertThat(result.getSubject()).as(scenario).isEqualTo(expected);
        assertThat(governance.produceEmailApplyingDefaultsAndOverrides(result).getSubject()).as(scenario + " repeated completion").isEqualTo(expected);
        assertThat(mail.emailBuilder().copying(result).buildEmail().getSubject()).as(scenario + " copying").isEqualTo(expected);
        assertThat(provided.getSubject()).as(scenario + " original input").isEqualTo(submitted);
    }

    private static Stream<Arguments> subjectRules() {
        final ConnectionConflictPolicy strict = ConnectionConflictPolicy.REJECT_CONFLICTS;
        final ConnectionConflictPolicy overrides = ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES;
        return Stream.of(
                Arguments.of("all candidates absent", null, null, null, Suppression.NONE, strict, null),
                Arguments.of("default supplies an absent subject", "default", null, null, Suppression.NONE, strict, "default"),
                Arguments.of("submitted subject stands alone", null, "submitted", null, Suppression.NONE, strict, "submitted"),
                Arguments.of("override stands alone", null, null, "override", Suppression.NONE, strict, "override"),
                Arguments.of("override wins all three sources", "default", "submitted", "override", Suppression.NONE, strict, "override"),
                Arguments.of("submitted beats default", "default", "submitted", null, Suppression.NONE, strict, "submitted"),
                Arguments.of("equal candidates remain ordinary selection", "same", "same", "same", Suppression.NONE, strict, "same"),
                Arguments.of("empty submitted text is present", "default", "", null, Suppression.NONE, strict, ""),
                Arguments.of("empty override text is present", "default", "submitted", "", Suppression.NONE, strict, ""),
                Arguments.of("empty default text is present", "", null, null, Suppression.NONE, strict, ""),
                Arguments.of("global default suppression", "default", null, null, Suppression.GLOBAL_DEFAULTS, strict, null),
                Arguments.of("field default suppression", "default", null, null, Suppression.DEFAULT_SUBJECT, strict, null),
                Arguments.of("global override suppression keeps submitted", "default", "submitted", "override", Suppression.GLOBAL_OVERRIDES, strict, "submitted"),
                Arguments.of("field override suppression keeps submitted", "default", "submitted", "override", Suppression.OVERRIDE_SUBJECT, strict, "submitted"),
                Arguments.of("global override suppression permits default", "default", null, "override", Suppression.GLOBAL_OVERRIDES, strict, "default"),
                Arguments.of("field override suppression permits default", "default", null, "override", Suppression.OVERRIDE_SUBJECT, strict, "default"),
                Arguments.of("default suppression leaves override eligible", "default", null, "override", Suppression.DEFAULT_SUBJECT, strict, "override"),
                Arguments.of("global exclusions retain submitted text", "default", "submitted", "override", Suppression.BOTH_GLOBAL, strict, "submitted"),
                Arguments.of("field exclusions retain submitted text", "default", "submitted", "override", Suppression.BOTH_SUBJECT, strict, "submitted"),
                Arguments.of("global exclusions with no submitted value", "default", null, "override", Suppression.BOTH_GLOBAL, strict, null),
                Arguments.of("field exclusions with no submitted value", "default", null, "override", Suppression.BOTH_SUBJECT, strict, null),
                Arguments.of("combined default exclusions retain submitted", "default", "submitted", null, Suppression.DEFAULTS_TWICE, strict, "submitted"),
                Arguments.of("combined override exclusions permit default", "default", null, "override", Suppression.OVERRIDES_TWICE, strict, "default"),
                Arguments.of("global default and field override exclusion", "default", null, "override", Suppression.MIXED_DEFAULT_GLOBAL, strict, null),
                Arguments.of("global override and field default exclusion", "default", null, "override", Suppression.MIXED_OVERRIDE_GLOBAL, strict, null),
                Arguments.of("connection override mode does not change Email precedence", "default", "submitted", "override", Suppression.NONE, overrides, "override"));
    }

    private static EmailTemplateOptions options(final Suppression suppression) {
        final EmailTemplateOptions.Builder options = EmailTemplateOptions.builder();
        switch (suppression) {
            case GLOBAL_DEFAULTS: options.ignoringDefaults(); break;
            case DEFAULT_SUBJECT: options.dontApplyDefaultValueFor(EmailProperty.SUBJECT); break;
            case GLOBAL_OVERRIDES: options.ignoringOverrides(); break;
            case OVERRIDE_SUBJECT: options.dontApplyOverrideValueFor(EmailProperty.SUBJECT); break;
            case BOTH_GLOBAL: options.ignoringDefaults().ignoringOverrides(); break;
            case BOTH_SUBJECT: options.dontApplyDefaultValueFor(EmailProperty.SUBJECT).dontApplyOverrideValueFor(EmailProperty.SUBJECT); break;
            case DEFAULTS_TWICE: options.ignoringDefaults().dontApplyDefaultValueFor(EmailProperty.SUBJECT); break;
            case OVERRIDES_TWICE: options.ignoringOverrides().dontApplyOverrideValueFor(EmailProperty.SUBJECT); break;
            case MIXED_DEFAULT_GLOBAL: options.ignoringDefaults().dontApplyOverrideValueFor(EmailProperty.SUBJECT); break;
            case MIXED_OVERRIDE_GLOBAL: options.ignoringOverrides().dontApplyDefaultValueFor(EmailProperty.SUBJECT); break;
            case NONE: break;
            default: throw new AssertionError(suppression);
        }
        return options.build();
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
