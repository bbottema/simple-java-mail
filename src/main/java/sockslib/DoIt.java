package sockslib;

import sockslib.server.BasicSocksProxyServer;

import java.io.IOException;

public class DoIt {
	public static void main(String[] args)
			throws IOException {
		BasicSocksProxyServer proxyServer = new BasicSocksProxyServer(1080);
		proxyServer.start();
	}
}
