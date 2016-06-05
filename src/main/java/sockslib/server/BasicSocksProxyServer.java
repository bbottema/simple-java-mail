package sockslib.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BasicSocksProxyServer extends Thread {

	private static final Logger logger = LoggerFactory.getLogger(BasicSocksProxyServer.class);

	private final ExecutorService threadPool = Executors.newFixedThreadPool(100);

	private final ServerSocket serverSocket;

	public BasicSocksProxyServer(@SuppressWarnings("SameParameterValue") int port)
			throws IOException  {
		serverSocket = new ServerSocket(port);
		setName("fs-thread");
		setDaemon(false);
	}

	@Override
	public void run() {
		logger.info("Start proxy server at port:{}", serverSocket.getLocalPort());
		//noinspection InfiniteLoopStatement
		while (true) {
			try {
				Socket socket = serverSocket.accept();
				logger.trace("new session ----------------------------------------------------------------");
				socket.setSoTimeout(10000);
				SocksSession session = new SocksSession(socket);
				Socks5Handler socksHandler = new Socks5Handler();
				// initialize socks handler
				socksHandler.setSession(session);

				threadPool.execute(socksHandler);

			} catch (IOException e) {
				logger.debug(e.getMessage(), e);
			}
		}
	}
}