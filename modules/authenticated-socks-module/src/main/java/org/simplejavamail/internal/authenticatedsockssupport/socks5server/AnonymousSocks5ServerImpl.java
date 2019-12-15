/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @see AnonymousSocks5Server
 */
public class AnonymousSocks5ServerImpl implements AnonymousSocks5Server {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AnonymousSocks5Server.class);
	
	private final Socks5Bridge socks5Bridge;
	private final int proxyBridgePort;
	
	private ExecutorService threadPool;
	private ServerSocket serverSocket;
	private boolean stopping = false;
	private boolean running = false;
	
	public AnonymousSocks5ServerImpl(final Socks5Bridge socks5Bridge, final int proxyBridgePort) {
		this.socks5Bridge = socks5Bridge;
		this.proxyBridgePort = proxyBridgePort;
	}
	
	/**
	 * @see AnonymousSocks5Server#start()
	 */
	@Override
	public void start() {
		if (running) {
			throw new IllegalStateException("server already running!");
		}
		running = true;
		try {
			this.threadPool = Executors.newFixedThreadPool(100);
			this.serverSocket = new ServerSocket();
			this.serverSocket.setReuseAddress(true);
			this.serverSocket.bind(new InetSocketAddress(proxyBridgePort));
		} catch (final IOException e) {
			throw new SocksException("error preparing socks5bridge server for authenticated proxy session", e);
		}
		new Thread(this).start();
	}
	
	@Override
	public void stop() {
		stopping = true;
		try {
			serverSocket.close();
		} catch (final IOException e) {
			throw new SocksException(e.getMessage(), e);
		}
	}
	
	@Override
	public void run() {
		LOGGER.info("Starting proxy server at port {}", serverSocket.getLocalPort());
		while (!stopping) {
			try {
				LOGGER.info("waiting for new connection...");
				@SuppressWarnings("SocketOpenedButNotSafelyClosed") // socket is closed elsewhere
				final Socket socket = serverSocket.accept();
				socket.setSoTimeout(10000);
				threadPool.execute(new Socks5Handler(new SocksSession(socket), socks5Bridge));
			} catch (final IOException e) {
				checkIoException(e);
			}
		}
		LOGGER.debug("shutting down...");
		threadPool.shutdownNow();
		running = false;
		stopping = false;
	}
	
	private void checkIoException(final Exception e) {
		if (e.getMessage().equalsIgnoreCase("socket closed")) {
			LOGGER.debug("socket closed");
		} else {
			running = false;
			stopping = false;
			throw new SocksException("server crashed...", e);
		}
	}
	
	@Override
	public boolean isStopping() {
		return stopping;
	}
	
	@Override
	public boolean isRunning() {
		return running;
	}
}