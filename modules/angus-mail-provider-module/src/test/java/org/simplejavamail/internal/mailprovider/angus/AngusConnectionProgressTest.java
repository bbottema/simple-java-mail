package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Session;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter.ConnectionProgressRegistration;
import org.simplejavamail.api.mailer.spi.MailTransportProgress;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;
import static org.simplejavamail.api.mailer.MailSendProgressStage.AUTHENTICATING;

/** Exercises both protected command paths without inspecting credentials or replacing any protocol implementation. */
@Timeout(20)
class AngusConnectionProgressTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authPrefixIsRecognizedOnStringAndByteCommandPaths(final boolean byteCommand) throws Exception {
        final CountDownLatch authenticating = new CountDownLatch(1);
        final MailTransportProgress progress = mock(MailTransportProgress.class);
        doAnswer(call -> { authenticating.countDown(); return null; }).when(progress).stageChanged(AUTHENTICATING);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(10000);
            final FutureTask<Void> peer = new FutureTask<>(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(10000);
                    final BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    final PrintWriter output = new PrintWriter(socket.getOutputStream(), true);
                    output.print("220 localhost\r\n"); output.flush();
                    assertThat(input.readLine()).isEqualTo("EHLO progress.example.test");
                    output.print("250-localhost\r\n250 AUTH LOGIN\r\n"); output.flush();
                    assertThat(input.readLine()).isEqualTo("AUTH LOGIN");
                    assertThat(authenticating.await(5, SECONDS)).isTrue();
                    output.print("334 VXNlcm5hbWU6\r\n"); output.flush();
                    assertThat(input.readLine()).isNotBlank();
                    output.print("334 UGFzc3dvcmQ6\r\n"); output.flush();
                    assertThat(input.readLine()).isNotBlank();
                    output.print("235 authenticated\r\n"); output.flush();
                    assertThat(input.readLine()).isEqualTo("QUIT");
                    output.print("221 bye\r\n"); output.flush();
                }
                return null;
            });
            new Thread(peer, "authentication-progress-peer").start();
            final Properties properties = new Properties();
            properties.setProperty("mail.smtp.localhost", "progress.example.test");
            properties.setProperty("mail.smtp.auth.mechanisms", "LOGIN");
            properties.setProperty("mail.smtp.timeout", "10000");
            // Construct in place: copying a transport into a spy would leave Angus's inner authenticators bound to the original instance.
            try (ManagedAngusTransport transport = mock(ManagedAngusTransport.class,
                    withSettings().useConstructor(Session.getInstance(properties), null).defaultAnswer(CALLS_REAL_METHODS))) {
                if (byteCommand) {
                    // Route the same AUTH exchange through the byte overload; normal LOGIN also tests non-AUTH payload byte commands.
                    doAnswer(call -> transport.simpleCommand(((String) call.getArgument(0)).getBytes(StandardCharsets.US_ASCII)))
                            .when(transport).simpleCommand(startsWith("AUTH "));
                }
                final ConnectionProgressRegistration registration = transport.observeConnectionSetup(progress);
                assertThat(registration).isNotNull();
                try {
                    transport.connect(listener.getInetAddress().getHostAddress(), listener.getLocalPort(), "private-user", "private-password");
                } finally {
                    registration.close();
                    registration.close();
                }
                verify(progress).stageChanged(AUTHENTICATING);
                verify(progress, never()).contentBytesWritten(anyLong());
            }
            peer.get(5, SECONDS);
        }
    }
}
