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

package sockslib.common;

import java.io.IOException;

/**
 * The class <code>SocksException</code> represents an exception about SOCKS protocol.
 *
 * @author Youchao Feng
 * @version 1.0
 */
public class SocksException extends IOException {

  /**
   * Messages that server will reply.
   */
  private static final String serverReplyMessage[] =
      {"General SOCKS server failure", "Connection not allowed by ruleset",
          "Network " + "unreachable", "Host unreachable", "Connection refused", "TTL expired",
          "Command not " + "supported", "Address type not supported"};

  /**
   * Constructs an instance of {@link SocksException} with a message.
   *
   * @param msg Message.
   */
  public SocksException(String msg) {
    super(msg);
  }

  /**
   * Returns a {@link SocksException} instance with a message of reply.
   *
   * @param reply Code of server's reply.
   * @return An instance of {@link SocksException}.
   */
  public static SocksException serverReplyException(byte reply) {
    int code = reply;
    code = code & 0xff;
    if (code < 0 || code > 0x08) {
      return new SocksException("Unknown reply");
    }
    code = code - 1;
    return new SocksException(serverReplyMessage[code]);
  }


}
