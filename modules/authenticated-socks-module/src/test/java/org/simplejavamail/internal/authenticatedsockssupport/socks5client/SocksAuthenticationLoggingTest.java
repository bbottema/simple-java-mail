package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class SocksAuthenticationLoggingTest {

    @Test
    void sendsCredentialsWithoutPublishingThemInTraceOutput() throws Exception {
        final String username = "proxy-user-\u00fc";
        final String password = "proxy-secret-\u2603";
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        final Socks5 proxy = new Socks5(new InetSocketAddress("localhost", 1080));
        proxy.setCredentials(new ProxyCredentials(username, password));
        proxy.setProxySocket(new Socket() {
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[] {1, 0}); }
            @Override public OutputStream getOutputStream() { return request; }
        });
        final CapturingAppender appender = new CapturingAppender();
        final org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(SocksAuthenticationHelper.class);
        final Level previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        try {
            SocksAuthenticationHelper.performUserPasswordAuthentication(proxy);

            final ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write(1);
            expected.write(username.getBytes(UTF_8).length);
            expected.write(username.getBytes(UTF_8));
            expected.write(password.getBytes(UTF_8).length);
            expected.write(password.getBytes(UTF_8));
            assertThat(request.toByteArray()).isEqualTo(expected.toByteArray());
            assertThat(appender.messages).isNotEmpty().allSatisfy(message ->
                    assertThat(message).doesNotContain(username, password, hex(username), hex(password)));
        } finally {
            logger.setLevel(previousLevel);
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    private static String hex(final String value) {
        final StringBuilder result = new StringBuilder();
        for (final byte octet : value.getBytes(UTF_8)) {
            result.append(Integer.toHexString(octet & 0xff)).append(' ');
        }
        return result.toString();
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new ArrayList<>();

        private CapturingAppender() {
            super("socks-authentication-logging-test", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override public void append(final LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }
}
