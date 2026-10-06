package org.simplejavamail.managedangus.factories;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/** Verifies public factory-method access across a qualified module export, without connecting. */
public class ExportedTlsFactory extends SSLSocketFactory {
    public static final IOException REJECTION = new IOException("fixture wrapping rejection");

    public static SSLSocketFactory getDefault() {
        return new ExportedTlsFactory();
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        throw REJECTION;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return new String[0];
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return new String[0];
    }

    @Override
    public Socket createSocket(String host, int port) {
        throw new UnsupportedOperationException("No network access in this fixture");
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
        throw new UnsupportedOperationException("No network access in this fixture");
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress local, int localPort) {
        throw new UnsupportedOperationException("No network access in this fixture");
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
        throw new UnsupportedOperationException("No network access in this fixture");
    }
}
