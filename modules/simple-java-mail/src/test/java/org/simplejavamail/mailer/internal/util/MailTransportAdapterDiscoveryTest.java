package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailTransportAdapterDiscoveryTest {
    @TempDir
    Path directory;

    @Test
    void discoveryIsReusedButEveryTransportInstanceIsMatchedWithAFreshAdapter() throws Exception {
        final Session session = Session.getInstance(new Properties());
        final PreparedMail mail = new PreparedMail(new MimeMessage(session), new Address[]{new InternetAddress("receiver@example.org")},
                new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);
        final Path registration = directory.resolve("adapters");
        Files.writeString(registration, StatefulAdapter.class.getName());
        final AtomicInteger scans = new AtomicInteger();
        final Thread caller = Thread.currentThread();
        final ClassLoader previous = caller.getContextClassLoader();
        final ClassLoader loader = new ClassLoader(previous) {
            @Override
            public Enumeration<URL> getResources(final String name) throws IOException {
                if (name.equals("META-INF/services/" + MailTransportAdapter.class.getName())) {
                    scans.incrementAndGet();
                    return Collections.enumeration(List.of(registration.toUri().toURL()));
                }
                return super.getResources(name);
            }
        };
        try {
            caller.setContextClassLoader(loader);
            for (final boolean supports : new boolean[]{true, false, true, false}) {
                final RecordingTransport transport = new RecordingTransport(session, supports);
                MailTransportAdapterResolver.sendMessage(transport, mail);
                assertThat(transport.usedAdapter).isEqualTo(supports);
                assertThat(transport.usedFallback).isNotEqualTo(supports);
            }
            assertThat(scans).hasValue(1);
        } finally {
            caller.setContextClassLoader(previous);
        }
    }

    @Test
    void ambiguityStillFailsBeforeAnySubmissionAfterDiscoveryIsCached() throws Exception {
        final Session session = Session.getInstance(new Properties());
        final PreparedMail mail = new PreparedMail(new MimeMessage(session), new Address[0],
                new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);
        final Path registration = directory.resolve("ambiguous-adapters");
        Files.write(registration, List.of(StatefulAdapter.class.getName(), SecondAdapter.class.getName()));
        final Thread caller = Thread.currentThread();
        final ClassLoader previous = caller.getContextClassLoader();
        try {
            caller.setContextClassLoader(new ClassLoader(previous) {
                @Override
                public Enumeration<URL> getResources(final String name) throws IOException {
                    return name.equals("META-INF/services/" + MailTransportAdapter.class.getName())
                            ? Collections.enumeration(List.of(registration.toUri().toURL())) : super.getResources(name);
                }
            });
            final RecordingTransport transport = new RecordingTransport(session, true);
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> MailTransportAdapterResolver.sendMessage(transport, mail))
                        .hasMessageContaining("Multiple mail transport adapters");
            }
            assertThat(transport.usedAdapter).isFalse();
            assertThat(transport.usedFallback).isFalse();
        } finally {
            caller.setContextClassLoader(previous);
        }
    }

    public static class StatefulAdapter implements MailTransportAdapter {
        private boolean consulted;

        @Override
        public boolean supports(final Transport transport) {
            assertThat(consulted).as("adapter instance is private to this operation").isFalse();
            consulted = true;
            return ((RecordingTransport) transport).supportsAdapter;
        }

        @Override
        public MailTransportResult sendMessage(final Transport transport, final PreparedMail preparedMail) {
            ((RecordingTransport) transport).usedAdapter = true;
            return MailTransportResult.accepted(preparedMail.getRecipients(), null);
        }
    }

    public static final class SecondAdapter extends StatefulAdapter { }

    private static final class RecordingTransport extends Transport {
        private final boolean supportsAdapter;
        private boolean usedAdapter;
        private boolean usedFallback;

        private RecordingTransport(final Session session, final boolean supportsAdapter) {
            super(session, new URLName("test", null, -1, null, null, null));
            this.supportsAdapter = supportsAdapter;
        }

        @Override
        public void sendMessage(final Message message, final Address[] recipients) {
            usedFallback = true;
        }
    }
}
