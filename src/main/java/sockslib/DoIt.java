package sockslib;

import sockslib.client.SSLSocks5;
import sockslib.client.Socks5;
import sockslib.client.SocksProxy;
import sockslib.client.SocksSocket;
import sockslib.common.KeyStoreInfo;
import sockslib.common.SSLConfiguration;
import sockslib.common.UsernamePasswordCredentials;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

public class DoIt {
	public static void main(String[] args)
			throws IOException {
		Socket socketAnonymous = createSocketPlainAnonymous();
		Socket socketAuth = createSocketAuthenticated();
		Socket socketSSLAnonymous = createSocketSSL();
		Socket socketSSLAuth = createSocketSSLAuthenticated();

		if (socketAnonymous != null) {
			System.out.println("socketAnonymous: " + socketAnonymous);
			testSocket(socketAnonymous);
		}
		if (socketAuth != null) {
			System.out.println("socketAuth: " + socketAuth);
			testSocket(socketAuth);
		}
		if (socketSSLAnonymous != null) {
			System.out.println("socketSSLAnonymous: " + socketSSLAnonymous);
			testSocket(socketSSLAnonymous);
		}
		if (socketSSLAuth != null) {
			System.out.println("socketSSLAuth: " + socketSSLAuth);
			testSocket(socketSSLAuth);
		}
	}

	private static Socket createSocketSSLAuthenticated() {
		try {
			SSLSocks5 socksProxySSLAuth = new SSLSocks5(new InetSocketAddress("localhost", 1030),
					new SSLConfiguration(null, new KeyStoreInfo("client-trust-keystore.jks", "123456", "JKS")));
			socksProxySSLAuth.setCredentials(new UsernamePasswordCredentials("PANCAKE", "letmein"));
			return new SocksSocket(socksProxySSLAuth, new InetSocketAddress("whois.internic.net", 43));
		} catch (Exception e) {
			System.out.println("socketSSLAuth failed");
			System.out.println(e.getMessage());
		}
		return null;
	}

	private static Socket createSocketSSL() {
		try {
			SSLSocks5 socksProxySSLAnonymous = new SSLSocks5(new InetSocketAddress("localhost", 1030),
					new SSLConfiguration(null, new KeyStoreInfo("client-trust-keystore.jks", "123456", "JKS")));
			return new SocksSocket(socksProxySSLAnonymous, new InetSocketAddress("whois.internic.net", 43));
		} catch (Exception e) {
			System.out.println("socketSSLAnonymous failed");
			System.out.println(e.getMessage());
		}
		return null;
	}

	private static Socket createSocketAuthenticated() {
		try {
			SocksProxy proxyAuth = new Socks5(new InetSocketAddress("localhost", 1080));
			proxyAuth.setCredentials(new UsernamePasswordCredentials("PANCAKE", "letmein"));
			Socket socketAuth = new SocksSocket(
					proxyAuth); // refactor to: new SocksSocket(proxy1, new InetSocketAddress("whois.internic.net", 43))
			socketAuth.connect(new InetSocketAddress("whois.internic.net", 43)); // refactor out (see line above)
			return socketAuth;
		} catch (Exception e) {
			System.out.println("socketAuth failed");
			System.out.println(e.getMessage());
		}
		return null;
	}

	private static Socket createSocketPlainAnonymous() {
		try {
			Socks5 socksProxyAnonymous = new Socks5(new InetSocketAddress("localhost", 1030));
			return new SocksSocket(socksProxyAnonymous, new InetSocketAddress("whois.internic.net", 43));
		} catch (Exception e) {
			System.out.println("socketAnonymous failed");
			System.out.println(e.getMessage());
		}
		return null;
	}

	private static void testSocket(Socket socketToTest)
			throws IOException {
		InputStreamReader isr = new InputStreamReader(socketToTest.getInputStream());
		BufferedReader in = new BufferedReader(isr);

		PrintWriter out = new PrintWriter(socketToTest.getOutputStream(), true);
		out.println("google.com");

		String line;
		while ((line = in.readLine()) != null) {
			System.out.println(line);
		}
	}

}
