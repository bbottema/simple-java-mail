package testutil.smtp;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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

    /** Positive TLS fixtures also trust the configured system roots, including developer mail shields that re-sign localhost certificates. */
    public static KeyStore testTrustStoreWithSystemRoots() throws Exception {
        final KeyStore trustStore = testKeyStore();
        final TrustManagerFactory systemTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        systemTrust.init((KeyStore) null);
        int certificateIndex = 0;
        for (final TrustManager manager : systemTrust.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                for (final X509Certificate certificate : ((X509TrustManager) manager).getAcceptedIssuers()) {
                    trustStore.setCertificateEntry("system-root-" + certificateIndex++, certificate);
                }
            }
        }
        return trustStore;
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
