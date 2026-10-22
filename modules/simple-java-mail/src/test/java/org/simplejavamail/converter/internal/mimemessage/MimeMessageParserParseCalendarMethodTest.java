package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimePart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MimeMessageParserParseCalendarMethodTest {

    @Test
    public void testMethodFoundInContentType() throws Exception {
        MimePart mockMimePart = mock(MimePart.class);
        DataHandler mockDataHandler = mock(DataHandler.class);
        
        // Mock Content-Type with METHOD
        when(mockMimePart.getDataHandler()).thenReturn(mockDataHandler);
        when(mockDataHandler.getContentType()).thenReturn("text/calendar; method=REQUEST; charset=UTF-8");

        String calendarContent = "BEGIN:VCALENDAR\nMETHOD:REQUEST\nEND:VCALENDAR";

        assertThat(MimeMessageParser.parseCalendarMethod(mockMimePart, calendarContent)).isEqualTo("REQUEST");
    }

    @Test
    public void testMethodFoundInBody() throws Exception {
        MimePart mockMimePart = mock(MimePart.class);
        DataHandler mockDataHandler = mock(DataHandler.class);

        // Mock Content-Type without METHOD
        when(mockMimePart.getDataHandler()).thenReturn(mockDataHandler);
        when(mockDataHandler.getContentType()).thenReturn("text/calendar; charset=UTF-8");

        // Method only in calendar content
        String calendarContent = "BEGIN:VCALENDAR\nMETHOD:REQUEST\nEND:VCALENDAR";

        assertThat(MimeMessageParser.parseCalendarMethod(mockMimePart, calendarContent)).isEqualTo("REQUEST");
    }

    @Test
    public void testMethodNotFoundThrowsException() throws Exception {
        MimePart mockMimePart = mock(MimePart.class);
        DataHandler mockDataHandler = mock(DataHandler.class);

        // Mock Content-Type and Body without METHOD
        when(mockMimePart.getDataHandler()).thenReturn(mockDataHandler);
        when(mockDataHandler.getContentType()).thenReturn("text/calendar; charset=UTF-8");

        // No method in the calendar body
        String calendarContent = "BEGIN:VCALENDAR\nEND:VCALENDAR";

        assertThatThrownBy(() -> MimeMessageParser.parseCalendarMethod(mockMimePart, calendarContent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Calendar METHOD not found");
    }

    @Test
    public void testMessagingExceptionThrown() throws Exception {
        MimePart mockMimePart = mock(MimePart.class);

        when(mockMimePart.getDataHandler()).thenThrow(new MessagingException("Failed to retrieve content type"));

        String calendarContent = "BEGIN:VCALENDAR\nMETHOD=REQUEST\nEND:VCALENDAR";

        assertThatThrownBy(() -> MimeMessageParser.parseCalendarMethod(mockMimePart, calendarContent))
                .isInstanceOf(MimeMessageParseException.class)
                .hasMessageContaining(MimeMessageParseException.ERROR_GETTING_CALENDAR_CONTENTTYPE);
    }

    @Test
    public void testMethodInBothContentTypeAndBody_ContentTypeTakesPriority() throws Exception {
        MimePart mockMimePart = mock(MimePart.class);
        DataHandler mockDataHandler = mock(DataHandler.class);

        // Mock Content-Type with METHOD
        when(mockMimePart.getDataHandler()).thenReturn(mockDataHandler);
        when(mockDataHandler.getContentType()).thenReturn("text/calendar; method=REQUEST; charset=UTF-8");

        // METHOD also present in calendar content, but different from Content-Type
        String calendarContent = "BEGIN:VCALENDAR\nMETHOD:CANCEL\nEND:VCALENDAR";

        assertThat(MimeMessageParser.parseCalendarMethod(mockMimePart, calendarContent)).isEqualTo("REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "text/calendar; method=REQUEST; incomplete",
            "text/calendar; ; method=REQUEST; incomplete",
            "text/calendar; method=REQUEST; broken=\"unterminated"
    })
    void retainsPreviouslyRecoverableMalformedHeaders(final String contentType) throws Exception {
        MimePart mockMimePart = mock(MimePart.class);
        DataHandler mockDataHandler = mock(DataHandler.class);
        when(mockMimePart.getDataHandler()).thenReturn(mockDataHandler);
        when(mockDataHandler.getContentType()).thenReturn(contentType);

        assertThat(MimeMessageParser.parseCalendarMethod(mockMimePart, "BEGIN:VCALENDAR\nMETHOD:CANCEL\nEND:VCALENDAR"))
                .isEqualTo("REQUEST");
    }

    @Test
    void retainsTheBodyFallbackWhenAMalformedHeaderHasNoMethod() throws Exception {
        MimePart mockMimePart = mock(MimePart.class);
        DataHandler mockDataHandler = mock(DataHandler.class);
        when(mockMimePart.getDataHandler()).thenReturn(mockDataHandler);
        when(mockDataHandler.getContentType()).thenReturn("text/calendar; incomplete");

        assertThat(MimeMessageParser.parseCalendarMethod(mockMimePart, "BEGIN:VCALENDAR\nMETHOD:REQUEST\nEND:VCALENDAR"))
                .isEqualTo("REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/calendar; method=\"\"", "text/calendar; method=\" \""})
    void blankHeaderMethodsKeepTheExistingBodyFallback(final String contentType) throws Exception {
        MimePart mockMimePart = mock(MimePart.class);
        DataHandler mockDataHandler = mock(DataHandler.class);
        when(mockMimePart.getDataHandler()).thenReturn(mockDataHandler);
        when(mockDataHandler.getContentType()).thenReturn(contentType);

        assertThat(MimeMessageParser.parseCalendarMethod(mockMimePart, "BEGIN:VCALENDAR\nMETHOD:REQUEST\nEND:VCALENDAR"))
                .isEqualTo("REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/calendar; method=\"REQUEST extra\"", "text/calendar; method=\"REQUEST-extra\""})
    void retainsTheHistoricallyAcceptedMethodPrefix(final String contentType) throws Exception {
        MimePart mockMimePart = mock(MimePart.class);
        DataHandler mockDataHandler = mock(DataHandler.class);
        when(mockMimePart.getDataHandler()).thenReturn(mockDataHandler);
        when(mockDataHandler.getContentType()).thenReturn(contentType);

        assertThat(MimeMessageParser.parseCalendarMethod(mockMimePart, "BEGIN:VCALENDAR\nMETHOD:CANCEL\nEND:VCALENDAR"))
                .isEqualTo("REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/calendar; name=\"method=REQUEST\"; incomplete", "text/calendar; x-method=REQUEST; incomplete"})
    void malformedHeaderRecoveryDoesNotPromoteOtherParameters(final String contentType) throws Exception {
        MimePart mockMimePart = mock(MimePart.class);
        DataHandler mockDataHandler = mock(DataHandler.class);
        when(mockMimePart.getDataHandler()).thenReturn(mockDataHandler);
        when(mockDataHandler.getContentType()).thenReturn(contentType);

        assertThat(MimeMessageParser.parseCalendarMethod(mockMimePart, "BEGIN:VCALENDAR\nMETHOD:CANCEL\nEND:VCALENDAR"))
                .isEqualTo("CANCEL");
    }
}
