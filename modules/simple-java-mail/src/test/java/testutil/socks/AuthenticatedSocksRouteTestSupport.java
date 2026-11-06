package testutil.socks;

import jakarta.mail.Session;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Ensures integration tests exercise the actual direct-socket provider rather than skipping a missing dependency. */
public final class AuthenticatedSocksRouteTestSupport {
    private AuthenticatedSocksRouteTestSupport() { }

    public static void requireDirectRoute() {
        assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(Session.getInstance(new Properties())))
                .as("the loaded Angus dependency must support direct authenticated SOCKS transport sockets").isTrue();
    }
}
