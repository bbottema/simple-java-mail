package org.simplejavamail.examples.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.examples.support.ProtectedContent;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandoffReceiverTest {
    @TempDir
    Path archive;

    @Test
    void brokerConnectionFailureCannotLeaveAnHttpHealthEndpointRunning() throws Exception {
        var unavailable = new IOException("broker is unavailable");
        var receiver = new HandoffReceiver(archive, true);
        try (var brokerFactory = mockStatic(HandoffApplication.class); var httpsFactory = mockStatic(HttpsServer.class)) {
            brokerFactory.when(HandoffApplication::brokerConnection).thenThrow(unavailable);

            assertSame(unavailable, assertThrows(IOException.class, receiver::start));

            httpsFactory.verifyNoInteractions();
        }
    }

    @Test
    void consumerRegistrationFailureRollsBackTheConnectionBeforeExposingHealth() throws Exception {
        Connection broker = mock(Connection.class);
        Channel consumer = mock(Channel.class);
        when(broker.createChannel()).thenReturn(consumer);
        var unavailable = new IOException("consumer registration failed");
        when(consumer.basicConsume(eq("prepared-mail"), eq(false), any(DeliverCallback.class), any(CancelCallback.class)))
                .thenThrow(unavailable);
        var receiver = new HandoffReceiver(archive, true);
        try (var brokerFactory = mockStatic(HandoffApplication.class); var httpsFactory = mockStatic(HttpsServer.class)) {
            brokerFactory.when(HandoffApplication::brokerConnection).thenReturn(broker);

            assertSame(unavailable, assertThrows(IOException.class, receiver::start));

            httpsFactory.verifyNoInteractions();
            verify(consumer).close();
            verify(broker).close();
        }
    }

    @Test
    void httpStartupHappensAfterConsumerRegistrationAndRollsItBackOnFailure() throws Exception {
        Connection broker = mock(Connection.class);
        Channel consumer = mock(Channel.class);
        when(broker.createChannel()).thenReturn(consumer);
        var order = new ArrayList<String>();
        when(consumer.basicConsume(eq("prepared-mail"), eq(false), any(DeliverCallback.class), any(CancelCallback.class)))
                .thenAnswer(invocation -> { order.add("consumer"); return "tag"; });
        var startupFailure = new IOException("HTTPS startup failed");
        var cleanupFailure = new IOException("channel cleanup failed");
        doThrow(cleanupFailure).when(consumer).close();
        var receiver = new HandoffReceiver(archive, true);
        try (var brokerFactory = mockStatic(HandoffApplication.class); var httpsFactory = mockStatic(HttpsServer.class)) {
            brokerFactory.when(HandoffApplication::brokerConnection).thenReturn(broker);
            httpsFactory.when(() -> HttpsServer.create(any(InetSocketAddress.class), eq(16))).thenAnswer(invocation -> {
                order.add("https");
                throw startupFailure;
            });

            assertSame(startupFailure, assertThrows(IOException.class, receiver::start));

            assertEquals(List.of("consumer", "https"), order);
            assertArrayEquals(new Throwable[]{cleanupFailure}, startupFailure.getSuppressed());
            verify(consumer).close();
            verify(broker).close();
            assertDoesNotThrow(receiver::close);
            verify(broker, times(1)).close();
        }
    }

    @Test
    void duplicateKeysRequireMatchingBytesAndEnvelope() {
        HandoffPackage prepared = preparedPackage();
        assertTrue(HandoffReceiver.matchesRetainedPackage(prepared, stored(prepared.sender(), prepared.recipients(), prepared.sha256())));
        assertFalse(HandoffReceiver.matchesRetainedPackage(prepared, stored("other@example.test", prepared.recipients(), prepared.sha256())));
        assertFalse(HandoffReceiver.matchesRetainedPackage(prepared, stored(prepared.sender(), List.of("different@example.test"), prepared.sha256())));
        assertFalse(HandoffReceiver.matchesRetainedPackage(prepared, stored(prepared.sender(), prepared.recipients(), "different")));
    }

    @Test
    void validationRejectsChangedBytesAndUnsafeKeysBeforeStorage() {
        HandoffPackage prepared = preparedPackage();
        assertDoesNotThrow(() -> HandoffReceiver.requireValidPackage(prepared));
        assertThrows(IllegalArgumentException.class, () -> HandoffReceiver.requireValidPackage(
                new HandoffPackage("../escape", prepared.sender(), prepared.recipients(), prepared.mime(), prepared.sha256())));
        assertThrows(IllegalArgumentException.class, () -> HandoffReceiver.requireValidPackage(
                new HandoffPackage(prepared.id(), prepared.sender(), prepared.recipients(), prepared.mime(), "wrong checksum")));
    }

    private HandoffPackage preparedPackage() {
        byte[] bytes = "synthetic bytes".getBytes(StandardCharsets.UTF_8);
        return new HandoffPackage("job", "sender@example.test", List.of("reader@example.test", "archive@example.test"),
                bytes, ProtectedContent.sha256(bytes));
    }

    private Map<String, Object> stored(String sender, List<String> recipients, String checksum) {
        return Map.of("sender", sender, "recipients", recipients, "sha256", checksum);
    }
}
