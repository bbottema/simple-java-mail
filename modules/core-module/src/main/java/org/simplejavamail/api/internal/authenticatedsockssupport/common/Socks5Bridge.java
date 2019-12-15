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
package org.simplejavamail.api.internal.authenticatedsockssupport.common;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Clean seperation between the server and client packages. This bridge acts as gateway from the temporary intermediary SOCKS5 server to the remote
 * proxy.
 * <p>
 * This Bridge connects the {@code AnonymousSocks5ServerImpl} server and the {@code Socks5} client.
 */
public interface Socks5Bridge {
	/**
	 * Generates a {@link Socket} using {@code Socks5} connected to authenticated proxy.
	 *
	 * @param sessionId           The current email session context.
	 * @param remoteServerAddress The target server that is behind the proxy.
	 * @param remoteServerPort    The target server's port that is behind the proxy.
	 *
	 * @return A socket that channels through an already authenticated SOCKS5 proxy.
	 * @throws IOException Thrown by the underlying Socket in case of connection issues.
	 */
	Socket connect(String sessionId, InetAddress remoteServerAddress, int remoteServerPort)
			throws IOException;
}
