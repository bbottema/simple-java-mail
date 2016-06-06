package sockslib;

import sockslib.client.SSLSocks5;
import sockslib.client.Socks5;
import sockslib.client.SocksSocket;
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
		Socket socket1 = test1(new Socks5(new InetSocketAddress("localhost", 1030)));
//		Socket socket2 = test1(new SSLSocks5(new InetSocketAddress("localhost", 1081),
//				new SSLConfiguration("client-trust-keystore.jks")));

		InputStreamReader isr = new InputStreamReader(socket1.getInputStream());
		BufferedReader in = new BufferedReader(isr);

		PrintWriter out = new PrintWriter(socket1.getOutputStream(), true);
		out.println("google.com");

		String line;
		while ((line = in.readLine()) != null) {
			System.out.println(line);
		}

		System.out.println(socket1);
//		System.out.println(socket2);
	}

	private static SocksSocket test1(Socks5 socks5)
			throws IOException {
		return new SocksSocket(socks5, new InetSocketAddress("whois.internic.net",43));
	}
}
