package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.CalendarMethod.CANCEL;
import static org.simplejavamail.api.email.CalendarMethod.PUBLISH;
import static org.simplejavamail.api.email.CalendarMethod.REQUEST;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.EmailProperty.CALENDAR_METHOD;
import static org.simplejavamail.api.email.EmailProperty.CALENDAR_TEXT;
import static org.simplejavamail.api.email.EmailProperty.SUBJECT;

class CalendarTemplateGovernanceTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @EnumSource(value = EmailProperty.class, names = {"CALENDAR_METHOD", "CALENDAR_TEXT"})
    void eitherDefaultSelectorOmitsTheDefaultCalendar(final EmailProperty selector) {
        final Email supplied = blank().withTemplateOptions(EmailTemplateOptions.builder()
                .dontApplyDefaultValueFor(selector).build()).buildEmail();
        final Email result = governance(calendar(REQUEST, "default invitation"), null)
                .produceEmailApplyingDefaultsAndOverrides(supplied);

        assertNoCalendar(result);
        assertThat(result.getPropertiesNotToApplyDefaultValueFor()).containsExactly(selector);
    }

    @ParameterizedTest
    @EnumSource(value = EmailProperty.class, names = {"CALENDAR_METHOD", "CALENDAR_TEXT"})
    void eitherOverrideSelectorRetainsTheSubmittedCalendar(final EmailProperty selector) {
        final Email supplied = blank().withCalendarText(PUBLISH, "supplied calendar")
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyOverrideValueFor(selector).build()).buildEmail();
        final Email result = governance(calendar(REQUEST, "default invitation"), calendar(CANCEL, "override cancellation"))
                .produceEmailApplyingDefaultsAndOverrides(supplied);

        assertCalendar(result, PUBLISH, "supplied calendar");
        assertThat(result.getPropertiesNotToApplyOverrideValueFor()).containsExactly(selector);
    }

    @ParameterizedTest
    @EnumSource(value = EmailProperty.class, names = {"CALENDAR_METHOD", "CALENDAR_TEXT"})
    void excludingAnOverrideCalendarAllowsTheWholeDefaultCalendar(final EmailProperty selector) {
        final Email supplied = blank().withTemplateOptions(EmailTemplateOptions.builder()
                .dontApplyOverrideValueFor(selector).build()).buildEmail();
        final Email result = governance(calendar(REQUEST, "default invitation"), calendar(CANCEL, "override cancellation"))
                .produceEmailApplyingDefaultsAndOverrides(supplied);

        assertCalendar(result, REQUEST, "default invitation");
    }

    @ParameterizedTest
    @EnumSource(value = EmailProperty.class, names = {"CALENDAR_METHOD", "CALENDAR_TEXT"})
    void excludingDefaultsDoesNotExcludeTheOverrideCalendar(final EmailProperty selector) {
        final Email supplied = blank().withTemplateOptions(EmailTemplateOptions.builder()
                .dontApplyDefaultValueFor(selector).build()).buildEmail();
        final Email result = governance(calendar(REQUEST, "default invitation"), calendar(CANCEL, "override cancellation"))
                .produceEmailApplyingDefaultsAndOverrides(supplied);

        assertCalendar(result, CANCEL, "override cancellation");
    }

    @ParameterizedTest
    @EnumSource(value = EmailProperty.class, names = {"CALENDAR_METHOD", "CALENDAR_TEXT"})
    void excludingBothTemplateSourcesKeepsCallerContent(final EmailProperty selector) {
        final Email supplied = blank().withCalendarText(PUBLISH, "supplied calendar")
                .withSubject("supplied subject").withPlainText("supplied body")
                .withTemplateOptions(EmailTemplateOptions.builder()
                        .dontApplyDefaultValueFor(selector).dontApplyOverrideValueFor(selector).build()).buildEmail();
        final Email result = governance(calendar(REQUEST, "default invitation"), calendar(CANCEL, "override cancellation"))
                .produceEmailApplyingDefaultsAndOverrides(supplied);

        assertCalendar(result, PUBLISH, "supplied calendar");
        assertCalendar(supplied, PUBLISH, "supplied calendar");
        assertThat(result.getSubject()).isEqualTo("supplied subject");
        assertThat(result.getPlainText()).isEqualTo("supplied body");
    }

    @Test
    void completeCalendarsUseOverrideThenSubmittedThenDefaultPrecedence() {
        final Email defaults = calendar(REQUEST, "default invitation");
        final Email supplied = calendar(PUBLISH, "supplied calendar");
        final Email overrides = calendar(CANCEL, "override cancellation");

        assertCalendar(governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(supplied), CANCEL, "override cancellation");
        assertCalendar(governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(supplied), PUBLISH, "supplied calendar");
        assertCalendar(governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(blank().buildEmail()), REQUEST, "default invitation");
        assertCalendar(governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(null), REQUEST, "default invitation");
        assertNoCalendar(governance(null, null).produceEmailApplyingDefaultsAndOverrides(null));
        assertCalendar(defaults, REQUEST, "default invitation");
        assertCalendar(overrides, CANCEL, "override cancellation");
    }

    @Test
    void globalIgnoreFlagsRetainTheirOwnTemplateScope() {
        final Email defaults = calendar(REQUEST, "default invitation");
        final Email overrides = calendar(CANCEL, "override cancellation");
        final Email ignoreDefaults = blank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build()).buildEmail();
        final Email ignoreOverrides = blank().withTemplateOptions(EmailTemplateOptions.builder().ignoringOverrides().build()).buildEmail();
        final Email ignoreBoth = blank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().build()).buildEmail();

        assertNoCalendar(governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(ignoreDefaults));
        assertCalendar(governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(ignoreDefaults), CANCEL, "override cancellation");
        assertCalendar(governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(ignoreOverrides), REQUEST, "default invitation");
        assertNoCalendar(governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(ignoreBoth));
        assertCalendar(governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(
                mail.emailBuilder().copying(ignoreBoth).withCalendarText(PUBLISH, "supplied calendar").buildEmail()), PUBLISH, "supplied calendar");
    }

    @ParameterizedTest
    @EnumSource(value = EmailProperty.class, names = {"CALENDAR_METHOD", "CALENDAR_TEXT"})
    void allowingOneDefaultSelectorLeavesTheOtherStoredExclusionActive(final EmailProperty selector) {
        final EmailProperty other = selector == CALENDAR_METHOD ? CALENDAR_TEXT : CALENDAR_METHOD;
        final EmailTemplateOptions.Builder options = EmailTemplateOptions.builder()
                .dontApplyDefaultValueFor(selector).dontApplyDefaultValueFor(other).dontApplyDefaultValueFor()
                .allowDefaultValueFor(selector);

        assertThat(options.getPropertiesNotToApplyDefaultValueFor()).containsExactly(other);
        assertNoCalendar(governance(calendar(REQUEST, "default invitation"), null)
                .produceEmailApplyingDefaultsAndOverrides(blank().withTemplateOptions(options.build()).buildEmail()));
        options.allowDefaultValueFor(other);
        assertCalendar(governance(calendar(REQUEST, "default invitation"), null)
                .produceEmailApplyingDefaultsAndOverrides(blank().withTemplateOptions(options.build()).buildEmail()), REQUEST, "default invitation");

        options.dontApplyDefaultValueFor(selector, other, SUBJECT).clearDefaultValueSuppressions();
        assertThat(options.getPropertiesNotToApplyDefaultValueFor()).isEmpty();
        assertCalendar(governance(calendar(REQUEST, "default invitation"), null)
                .produceEmailApplyingDefaultsAndOverrides(blank().withTemplateOptions(options.build()).buildEmail()), REQUEST, "default invitation");
    }

    @ParameterizedTest
    @EnumSource(value = EmailProperty.class, names = {"CALENDAR_METHOD", "CALENDAR_TEXT"})
    void allowingOneOverrideSelectorLeavesTheOtherStoredExclusionActive(final EmailProperty selector) {
        final EmailProperty other = selector == CALENDAR_METHOD ? CALENDAR_TEXT : CALENDAR_METHOD;
        final EmailTemplateOptions.Builder options = EmailTemplateOptions.builder()
                .dontApplyOverrideValueFor(selector).dontApplyOverrideValueFor(other).dontApplyOverrideValueFor()
                .allowOverrideValueFor(selector);
        final EmailGovernance governance = governance(calendar(REQUEST, "default invitation"), calendar(CANCEL, "override cancellation"));

        assertThat(options.getPropertiesNotToApplyOverrideValueFor()).containsExactly(other);
        assertCalendar(governance.produceEmailApplyingDefaultsAndOverrides(blank().withTemplateOptions(options.build()).buildEmail()), REQUEST, "default invitation");
        options.allowOverrideValueFor(other);
        assertCalendar(governance.produceEmailApplyingDefaultsAndOverrides(blank().withTemplateOptions(options.build()).buildEmail()), CANCEL, "override cancellation");

        options.dontApplyOverrideValueFor(selector, other, SUBJECT).clearOverrideValueSuppressions();
        assertThat(options.getPropertiesNotToApplyOverrideValueFor()).isEmpty();
        assertCalendar(governance.produceEmailApplyingDefaultsAndOverrides(blank().withTemplateOptions(options.build()).buildEmail()), CANCEL, "override cancellation");
    }

    @ParameterizedTest
    @EnumSource(value = EmailProperty.class, names = {"CALENDAR_METHOD", "CALENDAR_TEXT"})
    void exclusionsSurviveCopyingSerializationAndRepeatedCompletion(final EmailProperty selector) throws Exception {
        final EmailTemplateOptions options = EmailTemplateOptions.builder()
                .dontApplyDefaultValueFor(selector).dontApplyOverrideValueFor(selector).build();
        final Email supplied = blank().withCalendarText(PUBLISH, "supplied calendar")
                .withTemplateOptions(options).buildEmail();
        final EmailGovernance governance = governance(calendar(REQUEST, "default invitation"), calendar(CANCEL, "override cancellation"));
        final Email once = governance.produceEmailApplyingDefaultsAndOverrides(supplied);
        final Email copied = mail.emailBuilder().copying(once).buildEmail();
        final Email restored = roundTrip(copied);
        final Email twice = governance.produceEmailApplyingDefaultsAndOverrides(restored);

        assertCalendar(twice, PUBLISH, "supplied calendar");
        assertThat(twice.getPropertiesNotToApplyDefaultValueFor()).containsExactly(selector);
        assertThat(twice.getPropertiesNotToApplyOverrideValueFor()).containsExactly(selector);

        final Email noCalendar = blank().withTemplateOptions(options).buildEmail();
        final Email completedWithoutCalendar = governance.produceEmailApplyingDefaultsAndOverrides(roundTrip(
                mail.emailBuilder().copying(governance.produceEmailApplyingDefaultsAndOverrides(noCalendar)).buildEmail()));
        assertNoCalendar(completedWithoutCalendar);
    }

    @Test
    void clearingCallerCalendarAllowsFallbackAndTemplateSuppressionIsNotNested() {
        final Email defaults = blank().withCalendarText(REQUEST, "default invitation")
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides()
                        .dontApplyDefaultValueFor(CALENDAR_TEXT).dontApplyOverrideValueFor(CALENDAR_METHOD).build()).buildEmail();
        final Email cleared = blank().withCalendarText(PUBLISH, "removed caller calendar").clearCalendarText().buildEmail();

        assertCalendar(governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(cleared), REQUEST, "default invitation");
        assertNoCalendar(cleared);
        assertCalendar(governance(null, defaults).produceEmailApplyingDefaultsAndOverrides(blank().buildEmail()), REQUEST, "default invitation");
    }

    @ParameterizedTest
    @EnumSource(value = EmailProperty.class, names = {"CALENDAR_METHOD", "CALENDAR_TEXT"})
    void calendarSuppressionDoesNotSuppressItsSeparateEncodingChoice(final EmailProperty selector) {
        final Email defaults = blank().withCalendarText(REQUEST, "default invitation")
                .withContentEncodingConfig(ContentEncodingConfig.builder().withCalendarTextContentTransferEncoding(BASE_64).build()).buildEmail();
        final Email supplied = blank().withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(selector).build()).buildEmail();
        final Email result = governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(supplied);

        assertNoCalendar(result);
        assertThat(result.getCalendarTextContentTransferEncoding()).isEqualTo(BASE_64);
    }

    @Test
    void mandatoryLocksStillApplyWhenCalendarTemplatesAreExcluded() throws Exception {
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.defaults.subject", "Company subject",
                "simplejavamail.locked.defaults.body.calendar.content.transfer.encoding", "base64")).load());
        final Email supplied = blank().withCalendarText(PUBLISH, "supplied calendar")
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides()
                        .dontApplyDefaultValueFor(CALENDAR_METHOD, SUBJECT).dontApplyOverrideValueFor(CALENDAR_TEXT).build()).buildEmail();
        try (Mailer mailer = locked.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(calendar(REQUEST, "default invitation")).withEmailOverrides(calendar(CANCEL, "override cancellation")).buildMailer()) {
            final Email result = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(supplied);

            assertCalendar(result, PUBLISH, "supplied calendar");
            assertThat(result.getSubject()).isEqualTo("Company subject");
            assertThat(result.getCalendarTextContentTransferEncoding()).isEqualTo(BASE_64);
        }
    }

    @Test
    void conversionRequiresExplicitGovernanceAndRehearsalUsesTheCalendarSelection() throws Exception {
        final Email supplied = blank().from("sender@example.org").to("recipient@example.org")
                .withCalendarText(PUBLISH, calendarText(PUBLISH))
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyOverrideValueFor(CALENDAR_METHOD).build()).buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(calendar(REQUEST, calendarText(REQUEST))).withEmailOverrides(calendar(CANCEL, calendarText(CANCEL))).buildMailer()) {
            final String plainExport = mail.converter().fromEmail(supplied).toEml();
            final String governedExport = mail.converter().fromEmail(supplied).withEmailGovernance(mailer.getEmailGovernance()).toEml();
            final Email exported = mail.converter().fromEml(governedExport).toEmail();

            assertCalendar(mail.converter().fromEml(plainExport).toEmail(), PUBLISH, calendarText(PUBLISH));
            assertCalendar(exported, PUBLISH, calendarText(PUBLISH));
            assertCalendar(mailer.rehearse(supplied).getEffectiveEmail(), PUBLISH, calendarText(PUBLISH));
        }
    }

    @Test
    void exactRehearsalKeepsSourceBytesWhileExplicitCompletionResolvesCalendarTemplates() throws Exception {
        final byte[] bytes = ("From: sender@example.org\r\nTo: recipient@example.org\r\n"
                + "Message-ID: <exact-calendar@example.org>\r\nSubject: Exact calendar boundary\r\n\r\nOriginal body\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        final Email exact = mail.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("recipient@example.org").buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(calendar(REQUEST, calendarText(REQUEST))).withEmailOverrides(calendar(CANCEL, calendarText(CANCEL))).buildMailer()) {
            assertThat(mailer.rehearse(exact).getEmlBytes()).containsExactly(bytes);
            assertNoCalendar(mailer.rehearse(exact).getEffectiveEmail());
            assertCalendar(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(exact), CANCEL, calendarText(CANCEL));
            assertNoCalendar(exact);
        }
    }

    private EmailPopulatingBuilder blank() {
        return mail.emailBuilder().startingBlank();
    }

    private Email calendar(final CalendarMethod method, final String text) {
        return blank().withCalendarText(method, text).buildEmail();
    }

    private EmailGovernance governance(final Email defaults, final Email overrides) {
        return new EmailGovernanceImpl(mail.getConfig(), mail.emailBuilder(), null, defaults, overrides, null);
    }

    private static void assertCalendar(final Email email, final CalendarMethod method, final String text) {
        assertThat(email.getCalendarMethod()).isEqualTo(method);
        assertThat(email.getCalendarText()).isEqualTo(text);
    }

    private static void assertNoCalendar(final Email email) {
        assertThat(email.getCalendarMethod()).isNull();
        assertThat(email.getCalendarText()).isNull();
    }

    private static String calendarText(final CalendarMethod method) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Simple Java Mail//Calendar governance//EN\r\nMETHOD:"
                + method.name() + "\r\nEND:VCALENDAR\r\n";
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(email);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }
}
