package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import testutil.ConfigLoaderTestHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.CalendarMethod.CANCEL;
import static org.simplejavamail.api.email.CalendarMethod.REQUEST;

class CalendarRequiredInputTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    enum MissingInput { METHOD, TEXT, BOTH }

    @ParameterizedTest
    @EnumSource(MissingInput.class)
    void rejectedReplacementPreservesThePairForInspectionCopyingAndCompletion(final MissingInput missing) {
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank().withCalendarText(REQUEST, "original");
        final Email original = draft.buildEmail();

        rejectReplacement(draft, missing);

        assertThat(draft.getCalendarMethod()).isEqualTo(REQUEST);
        assertThat(draft.getCalendarText()).isEqualTo("original");
        assertCalendar(original, REQUEST, "original");
        assertCalendar(draft.buildEmail(), REQUEST, "original");
        assertCalendar(mail.emailBuilder().copying(draft).buildEmail(), REQUEST, "original");
        assertCalendar(mail.emailBuilder().copying(original).buildEmail(), REQUEST, "original");
        assertCalendar(draft.buildEmailCompletedWithDefaultsAndOverrides(), REQUEST, "original");
    }

    @ParameterizedTest
    @EnumSource(MissingInput.class)
    void rejectedReplacementLeavesAnAbsentCalendarAbsent(final MissingInput missing) {
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank();

        rejectReplacement(draft, missing);

        assertThat(draft.getCalendarMethod()).isNull();
        assertThat(draft.getCalendarText()).isNull();
        assertNoCalendar(draft.buildEmail());
        assertNoCalendar(mail.emailBuilder().copying(draft).buildEmail());
        assertNoCalendar(draft.buildEmailCompletedWithDefaultsAndOverrides());
    }

    @Test
    void clearingAfterARejectedReplacementRemovesThePairAndPermitsTemplateFallback() throws Exception {
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank().withCalendarText(REQUEST, "original");
        final Email defaults = mail.emailBuilder().startingBlank().withCalendarText(CANCEL, "default calendar").buildEmail();

        rejectReplacement(draft, MissingInput.TEXT);
        draft.clearCalendarText();

        assertNoCalendar(draft.buildEmail());
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(defaults).buildMailer()) {
            assertCalendar(draft.buildEmailCompletedWithDefaultsAndOverrides(mailer.getEmailGovernance()), CANCEL, "default calendar");
        }
        assertNoCalendar(draft.buildEmail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"free-form calendar text", ""})
    void nonNullReplacementRetainsFreeFormTextAndEarlierSnapshots(final String replacement) {
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank().withCalendarText(REQUEST, "original");
        final Email original = draft.buildEmail();

        draft.withCalendarText(CANCEL, replacement);

        assertCalendar(original, REQUEST, "original");
        assertCalendar(draft.buildEmail(), CANCEL, replacement);
        assertCalendar(mail.emailBuilder().copying(draft).buildEmail(), CANCEL, replacement);
        assertCalendar(draft.buildEmailCompletedWithDefaultsAndOverrides(), CANCEL, replacement);
        draft.clearCalendarText();
        assertNoCalendar(draft.buildEmail());
        assertCalendar(original, REQUEST, "original");
    }

    private static void rejectReplacement(final EmailPopulatingBuilder draft, final MissingInput missing) {
        final CalendarMethod method = missing == MissingInput.TEXT ? CANCEL : null;
        final String text = missing == MissingInput.METHOD ? "replacement" : null;
        assertThatThrownBy(() -> draft.withCalendarText(method, text)).isInstanceOf(NullPointerException.class)
                .hasMessage(missing == MissingInput.TEXT ? "textCalendar" : "calendarMethod");
    }

    private static void assertCalendar(final Email email, final CalendarMethod method, final String text) {
        assertThat(email.getCalendarMethod()).isEqualTo(method);
        assertThat(email.getCalendarText()).isEqualTo(text);
    }

    private static void assertNoCalendar(final Email email) {
        assertThat(email.getCalendarMethod()).isNull();
        assertThat(email.getCalendarText()).isNull();
    }
}
