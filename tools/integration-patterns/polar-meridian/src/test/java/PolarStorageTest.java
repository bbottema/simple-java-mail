import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.examples.support.SqlStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PolarStorageTest {
    @Test
    void injectedResultWriteFailureIdentifiesTheCurrentJobAndAttempt() {
        SqlStore database = mock(SqlStore.class);
        when(database.rows("SELECT request_id FROM pm_attempts WHERE id=?", "attempt-123"))
                .thenReturn(List.of(Map.of("request_id", "current_observer")));
        PolarStorage storage = new PolarStorage(database);
        storage.failOutcomeWrites.set(true);

        assertThrows(IllegalStateException.class, () -> storage.recordOutcome("attempt-123", mock(MailSendOutcome.class)));

        verify(database).update("INSERT INTO pm_events(kind,detail) VALUES (?,?)", "RESULT_WRITE_FAILED",
                "requestId=current_observer; attemptId=attempt-123; The injected archive failure needs reconciliation; it must not resend SMTP");
    }
}
