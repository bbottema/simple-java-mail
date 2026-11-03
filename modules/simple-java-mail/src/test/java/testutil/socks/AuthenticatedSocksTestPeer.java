package testutil.socks;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static testutil.smtp.SmtpTestTls.testTrustStoreWithSystemRoots;

/** Remote RFC1929 proxy and SMTP endpoint, with normal JSSE TLS on the authenticated stream. */
public final class AuthenticatedSocksTestPeer implements AutoCloseable {
    public enum Protocol { SMTP, STARTTLS, SMTPS }

    private final Protocol protocol;
    private final ServerSocket listener;
    private final int port;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final List<String> destinations = new CopyOnWriteArrayList<>();
    private final List<String> serverNames = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger authenticated = new AtomicInteger();
    private final AtomicInteger authenticationAttempts = new AtomicInteger();
    private final AtomicInteger messages = new AtomicInteger();
    private final AtomicInteger tlsHandshakes = new AtomicInteger();
    private final Thread acceptor;
    private final SSLContext serverContext;
    private final boolean acceptCredentials;
    private final int connectReply;
    private volatile boolean stopping;

    public AuthenticatedSocksTestPeer(final Protocol protocol) throws Exception {
        this(protocol, true);
    }

    public AuthenticatedSocksTestPeer(final Protocol protocol, final boolean acceptCredentials) throws Exception {
        this(protocol, acceptCredentials, 0);
    }

    public AuthenticatedSocksTestPeer(final Protocol protocol, final boolean acceptCredentials, final int connectReply) throws Exception {
        this.protocol = protocol;
        this.acceptCredentials = acceptCredentials;
        this.connectReply = connectReply;
        serverContext = context(true);
        listener = new ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"));
        port = listener.getLocalPort();
        acceptor = new Thread(this::accept, "remote authenticated-SOCKS fixture listener");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() { return port; }
    public int accepted() { return accepted.get(); }
    public int authenticated() { return authenticated.get(); }
    public int authenticationAttempts() { return authenticationAttempts.get(); }
    public int messages() { return messages.get(); }
    public int tlsHandshakes() { return tlsHandshakes.get(); }
    public List<String> destinations() { return List.copyOf(destinations); }
    public List<String> serverNames() { return List.copyOf(serverNames); }
    public void assertHealthy() { assertThat(failures).as("remote proxy/SMTP fixture failures").isEmpty(); }

    /** Retain the allocated endpoint as a real refused-TCP availability witness. No client connected before this call. */
    public void refuseConnections() throws IOException {
        assertThat(accepted()).isZero();
        stopping = true;
        listener.close();
    }

    public static SSLSocketFactory trustedClientTlsFactory() throws Exception { return context(false).getSocketFactory(); }

    public static SSLSocketFactory untrustedClientTlsFactory() throws Exception {
        final KeyStore empty = KeyStore.getInstance("JKS");
        empty.load(null, null);
        final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(empty);
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    private static SSLContext context(final boolean server) throws Exception {
        final KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream source = AuthenticatedSocksTestPeer.class.getResourceAsStream("/smtp_test_server.jks")) {
            if (source == null) { throw new IOException("Missing SMTP TLS fixture"); }
            store.load(source, "changeit".toCharArray());
        }
        final SSLContext context = SSLContext.getInstance("TLS");
        if (server) {
            final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, "changeit".toCharArray());
            context.init(keys.getKeyManagers(), null, null);
        } else {
            final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(testTrustStoreWithSystemRoots());
            context.init(null, trust.getTrustManagers(), null);
        }
        return context;
    }

    private void accept() {
        try {
            while (!stopping) {
                final Socket socket = listener.accept();
                sockets.add(socket);
                accepted.incrementAndGet();
                workers.execute(() -> serve(socket));
            }
        } catch (IOException | RuntimeException failure) {
            if (!stopping) { failures.add(failure); }
        }
    }

    private void serve(final Socket socket) {
        try (Socket peer = socket) {
            peer.setSoTimeout(10000);
            peer.setTcpNoDelay(true);
            final DataInputStream input = new DataInputStream(peer.getInputStream());
            final OutputStream output = peer.getOutputStream();
            assertThat(input.readUnsignedByte()).isEqualTo(5);
            assertThat(input.readNBytes(input.readUnsignedByte())).contains((byte) 2);
            output.write(new byte[] {5, 2}); output.flush();
            assertThat(input.readUnsignedByte()).isEqualTo(1);
            assertThat(new String(input.readNBytes(input.readUnsignedByte()), UTF_8)).isEqualTo("proxy-user");
            assertThat(new String(input.readNBytes(input.readUnsignedByte()), UTF_8)).isEqualTo("proxy-secret");
            authenticationAttempts.incrementAndGet();
            if (!acceptCredentials) {
                output.write(new byte[] {1, 1}); output.flush();
                return;
            }
            authenticated.incrementAndGet();
            output.write(new byte[] {1, 0}); output.flush();
            assertThat(input.readNBytes(3)).containsExactly(5, 1, 0);
            final int type = input.readUnsignedByte();
            final String host;
            if (type == 3) { host = new String(input.readNBytes(input.readUnsignedByte()), UTF_8); }
            else {
                assertThat(type).isIn(1, 4);
                host = InetAddress.getByAddress(input.readNBytes(type == 1 ? 4 : 16)).getHostAddress();
            }
            final int port = input.readUnsignedShort();
            assertThat(port).isEqualTo(2525);
            destinations.add(host + ":" + port);
            output.write(new byte[] {5, (byte) connectReply, 0, 1, 127, 0, 0, 1, 9, (byte) 221}); output.flush();
            if (connectReply != 0) { return; }
            if (protocol == Protocol.SMTPS) {
                final SSLSocket tls = tls(peer);
                line(tls.getOutputStream(), "220 direct-fixture.example.test ESMTP");
                smtp(tls.getInputStream(), tls.getOutputStream());
            } else {
                line(output, "220 direct-fixture.example.test ESMTP");
                if (protocol == Protocol.STARTTLS) {
                    assertThat(readLine(input)).startsWith("EHLO ");
                    line(output, "250-fixture"); line(output, "250-STARTTLS"); line(output, "250 OK");
                    assertThat(readLine(input)).isEqualTo("STARTTLS");
                    line(output, "220 Begin TLS");
                    final SSLSocket tls = tls(peer);
                    smtp(tls.getInputStream(), tls.getOutputStream());
                } else { smtp(input, output); }
            }
        } catch (Throwable failure) {
            if (!stopping) { failures.add(failure); }
        } finally { sockets.remove(socket); }
    }

    private SSLSocket tls(final Socket socket) throws Exception {
        final SSLSocket tls = (SSLSocket) serverContext.getSocketFactory().createSocket(socket, "127.0.0.1", socket.getPort(), true);
        tls.setUseClientMode(false);
        final SSLParameters parameters = tls.getSSLParameters();
        parameters.setSNIMatchers(Collections.singleton(new SNIMatcher(0) {
            @Override public boolean matches(final SNIServerName name) { serverNames.add(((SNIHostName) name).getAsciiName()); return true; }
        }));
        tls.setSSLParameters(parameters);
        tls.startHandshake();
        tlsHandshakes.incrementAndGet();
        return tls;
    }

    private void smtp(final InputStream input, final OutputStream output) throws IOException {
        String command;
        while ((command = readLine(input)) != null) {
            final String upper = command.toUpperCase(Locale.ROOT);
            if (upper.startsWith("EHLO ") || upper.startsWith("HELO ") || upper.startsWith("MAIL FROM:")
                    || upper.startsWith("RCPT TO:") || upper.equals("RSET") || upper.equals("NOOP")) { line(output, "250 OK"); }
            else if (upper.equals("DATA")) {
                line(output, "354 Send message");
                while ((command = readLine(input)) != null && !command.equals(".")) { }
                if (command == null) { throw new EOFException("Truncated SMTP message"); }
                messages.incrementAndGet(); line(output, "250 queued");
            } else if (upper.equals("QUIT")) { line(output, "221 Bye"); return; }
            else { throw new IOException("Unexpected fixture SMTP command: " + command); }
        }
    }

    private static String readLine(final InputStream input) throws IOException {
        final StringBuilder line = new StringBuilder();
        int octet;
        while ((octet = input.read()) != -1) {
            if (octet == '\n') { return line.toString(); }
            if (octet != '\r') { line.append((char) octet); }
            if (line.length() > 8192) { throw new IOException("Fixture SMTP line exceeds bound"); }
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void line(final OutputStream output, final String line) throws IOException {
        output.write((line + "\r\n").getBytes(US_ASCII)); output.flush();
    }

    @Override public void close() throws Exception {
        stopping = true;
        listener.close();
        for (final Socket socket : sockets) { socket.close(); }
        workers.shutdownNow();
        acceptor.join(5000);
        assertThat(acceptor.isAlive()).isFalse();
        assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
    }
}
