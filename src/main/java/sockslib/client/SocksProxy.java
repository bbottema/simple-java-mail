/*
 * Copyright 2015-2025 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package sockslib.client;

import sockslib.common.SocksException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * The interface <code>SocksProxy</code> define a SOCKS proxy. it's will be used by
 *
 * @author Youchao Feng
 * @version 1.0
 */
public interface SocksProxy {

  /**
   * Default SOCKS server port.
   */
  int SOCKS_DEFAULT_PORT = 1080;

  /**
   * Get the socket which connect SOCKS server.
   *
   * @return java.net.Socket.
   */
  Socket getProxySocket();

  /**
   * Set a unconnected socket which will be used to connect SOCKS server.
   *
   * @param socket a unconnected socket.
   */
  void setProxySocket(Socket socket);

  /**
   * Get SOCKS Server port.
   *
   * @return server port.
   */
  int getPort();

  /**
   * Get SOCKS server's address as IPv4 or IPv6.
   *
   * @return server's IP address.
   */
  InetAddress getInetAddress();

  /**
   * Connect SOCKS server using SOCKS protocol. This method will ask SOCKS server to select
   * a method from the methods listed by client. If SOCKS server need authentication, it will
   * do authentication. If SOCKS server select 0xFF,It means that none of the methods listed by the
   * client are acceptable and this method should throw {@link SocksException}.
   *
   * @throws IOException    if any IO error occurs.
   * @throws SocksException if any error about SOCKS protocol occurs.
   */
  void buildConnection() throws IOException;

  /**
   * This method will send a CONNECT command to SOCKS server and ask SOCKS server to connect remote
   * server.
   *
   * @param address Remote server's address as java.net.InetAddress instance.
   * @param port    Remote server's port.
   * @throws SocksException If any error about SOCKS protocol occurs.
   * @throws IOException    If any I/O error occurs.
   */
  void requestConnect(InetAddress address, int port) throws
      IOException;

  /**
   * This method will send a CONNECT command to SOCKS server and ask SOCKS server to connect remote
   * server.
   *
   * @param address Remote server's address as java.net.SocketAddress instance.
   * @throws SocksException If any error about SOCKS protocol occurs.
   * @throws IOException    If any I/O error occurs.
   */
  void requestConnect(SocketAddress address) throws  IOException;

  /**
   * This method can build a same SocksProxy instance. The new instance created by this method has
   * the same properties with the original instance, but they have different socket instance. The
   * new instance's socket is also unconnected.
   *
   * @return The copy of this SocksProxy.
   */
  SocksProxy copy();

  /**
   * Returns the chain proxy.
   *
   * @return the chain proxy.
   */
  SocksProxy getChainProxy();

}
