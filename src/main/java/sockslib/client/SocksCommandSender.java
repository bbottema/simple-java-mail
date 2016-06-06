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
 * The interface <code>SocksCommandSender</code> can send SOCKS command to SOCKS server.
 *
 * @author Youchao Feng
 * @version 1.0
 * @see <a href="http://www.ietf.org/rfc/rfc1928.txt">SOCKS Protocol Version 5</a>
 */
interface SocksCommandSender {

  int RESERVED = 0x00;
  byte ATYPE_IPV4 = 0x01;
  byte ATYPE_IPV6 = 0x04;
  int REP_SUCCEEDED = 0x00;

  /**
   * Send a command to SOCKS server.
   *
   * @param socket  Socket that has connected SOCKS server.
   * @param address Remote server IPv4 or IPv6 address.
   * @param port    Remote server port.
   * @throws SocksException If any error about SOCKS protocol occurs.
   * @throws IOException    If any I/O error occurs.
   */
  void send(Socket socket, InetAddress address, int port) throws IOException;

  /**
   * Send a command to SOCKS server.
   *
   * @param socket  Socket that has connected SOCKS server.
   * @param address Remote server address.
   * @param version The version of SOCKS protocol.
   * @throws SocksException If any error about SOCKS protocol occurs.
   * @throws IOException    If any I/O error occurs.
   */
  void send(Socket socket, SocketAddress address, int version) throws IOException;

}
