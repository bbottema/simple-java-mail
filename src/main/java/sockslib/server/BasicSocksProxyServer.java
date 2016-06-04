package sockslib.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BasicSocksProxyServer implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(BasicSocksProxyServer.class);

	/**
	 * Thread pool used to process each connection.
	 */
	private final ExecutorService executorService = Executors.newFixedThreadPool(100);

	private ServerSocket serverSocket;

	@SuppressWarnings("FieldCanBeLocal")
	private Thread thread;

	@Override
	public void run() {
		logger.info("Start proxy server at port:{}", 1080);
		//noinspection InfiniteLoopStatement
		while (true) {
			try {
				Socket socket = serverSocket.accept();
				socket.setSoTimeout(10000);
				SocksSession session = new SocksSession(socket);
				Socks5Handler socksHandler = new Socks5Handler();
				// initialize socks handler
				socksHandler.setSession(session);

				executorService.execute(socksHandler);

			} catch (IOException e) {
				logger.debug(e.getMessage(), e);
			}
		}
	}

	public void start()
			throws IOException {
		serverSocket = new ServerSocket(1080);
		thread = new Thread(this);
		thread.setName("fs-thread");
		thread.setDaemon(false);
		thread.start();
	}
}