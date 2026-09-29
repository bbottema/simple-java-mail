package testutil.smtp;

import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.SSLContext;

import static java.util.Objects.requireNonNull;

/** Test-only identity and trust material for embedded SMTP peers. */
public final class SmtpTestTls {
    private SmtpTestTls() {
    }

    public static KeyStore testKeyStore() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream source = requireNonNull(SmtpTestTls.class.getResourceAsStream("/smtp_test_server.jks"))) {
            keyStore.load(source, "changeit".toCharArray());
        }
        return keyStore;
    }

    public static SSLContext tlsContext(final KeyStore trustStore) throws Exception {
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(testKeyStore(), "changeit".toCharArray());
        final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

}
