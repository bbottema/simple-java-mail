package sockslib;

import sockslib.client.SSLSocks5;
import sockslib.client.Socks5;
import sockslib.client.SocksSocket;
import sockslib.common.KeyStoreInfo;
import sockslib.common.SSLConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

public class DoIt {
	public static void main(String[] args)
			throws IOException {
//		Socket socket = test1(new Socks5(new InetSocketAddress("localhost", 1030)));
		Socket socket = test1(new SSLSocks5(new InetSocketAddress("localhost", 1030),
				new SSLConfiguration(null, new KeyStoreInfo("client-trust-keystore.jks", "123456", "JKS"))));

		InputStreamReader isr = new InputStreamReader(socket.getInputStream());
		BufferedReader in = new BufferedReader(isr);

		PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
		out.println("google.com");

		String line;
		while ((line = in.readLine()) != null) {
			System.out.println(line);
		}
	}

	private static SocksSocket test1(Socks5 socks5)
			throws IOException {
		return new SocksSocket(socks5, new InetSocketAddress("whois.internic.net",43));
	}
}
