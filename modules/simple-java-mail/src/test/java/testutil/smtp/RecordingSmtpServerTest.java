package testutil.smtp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Timeout(20)
class RecordingSmtpServerTest {
    @Test
    void unusedEndpointCanCloseWithoutWaitingForAnExpectedConnection() throws Exception {
        try (RecordingSmtpServer server = new RecordingSmtpServer()) {
            assertThat(server.connectionCount()).isZero();
        }
    }

    @Test
    void workerAssertionsReachTheTestThread() throws Exception {
        final AssertionError scriptFailure = new AssertionError("deliberate fixture assertion");
        final RecordingSmtpServer server = new RecordingSmtpServer((peer, connection) -> {
            throw scriptFailure;
        });
        try (Socket connection = new Socket("localhost", server.port())) {
            // EOF establishes that the worker reached its assertion and closed the accepted socket.
            assertThat(connection.getInputStream().read()).isEqualTo(-1);
        } finally {
            assertThat(catchThrowable(server::close)).hasRootCause(scriptFailure);
        }
    }
}
