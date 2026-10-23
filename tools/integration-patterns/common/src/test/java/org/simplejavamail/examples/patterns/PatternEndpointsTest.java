package org.simplejavamail.examples.patterns;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.config.ConfigLoader;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PatternEndpointsTest {
    private final JobStore jobs = mock(JobStore.class);
    private final PatternPorts.FeedbackSink sink = mock(PatternPorts.FeedbackSink.class);
    private final ObjectProvider<PatternPorts.FeedbackSink> feedback = mock(ObjectProvider.class);
    private final PatternEndpoints endpoints = new PatternEndpoints(SimpleJavaMail.withConfig(ConfigLoader.builder().load()),
            jobs, mock(PatternPorts.WorkTrigger.class), feedback);

    @Test
    void failedHandoffIsVisibleAndDuplicateReportsDoNotSilentlyRepublishIt() {
        when(jobs.recordFeedback(anyString(), eq("attempt-1"), eq("FAILED"), eq("5.1.1")))
                .thenReturn(new JobStore.FeedbackResult("RECORDED", "job-1"), new JobStore.FeedbackResult("DUPLICATE", null));
        invokeAvailableSink();
        doThrow(new IllegalStateException("Workflow service is unavailable")).when(sink).accept("job-1", "FAILED");

        Map<String, Object> first = endpoints.report(report());
        Map<String, Object> duplicate = endpoints.report(report());

        assertEquals("RECORDED_HANDOFF_FAILED", first.get("result"));
        assertEquals("DUPLICATE", duplicate.get("result"));
        verify(jobs).event(eq("job-1"), eq("FEEDBACK_HANDOFF_FAILED"), anyString());
        verify(sink, times(1)).accept("job-1", "FAILED");
    }

    @Test
    void failedReportPersistenceNeverCallsTheDownstreamSink() {
        RuntimeException unavailable = new IllegalStateException("SQL is unavailable");
        when(jobs.recordFeedback(anyString(), anyString(), anyString(), anyString())).thenThrow(unavailable);

        assertSame(unavailable, assertThrows(RuntimeException.class, () -> endpoints.report(report())));

        verifyNoInteractions(feedback, sink);
    }

    @SuppressWarnings("unchecked")
    private void invokeAvailableSink() {
        doAnswer(call -> {
            Consumer<PatternPorts.FeedbackSink> consumer = call.getArgument(0);
            consumer.accept(sink);
            return null;
        }).when(feedback).ifAvailable(any(Consumer.class));
    }

    private byte[] report() {
        return ("From: postmaster@example.test\r\n"
                + "To: sender@example.test\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/report; report-type=delivery-status; boundary=report\r\n\r\n"
                + "--report\r\nContent-Type: text/plain\r\n\r\nSynthetic failure\r\n"
                + "--report\r\nContent-Type: message/delivery-status\r\n\r\n"
                + "Reporting-MTA: dns; smtp.example.test\r\nOriginal-Envelope-ID: attempt-1\r\n\r\n"
                + "Final-Recipient: rfc822; reader@example.test\r\nAction: failed\r\nStatus: 5.1.1\r\n\r\n"
                + "--report--\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}
