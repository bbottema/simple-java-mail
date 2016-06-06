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
import sockslib.utils.LogMessageBuilder;
import sockslib.utils.LogMessageBuilder.MsgType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * The class <code>GenericSocksMethodRequester</code> implements {@link SocksMethodRequester}.
 *
 * @author Youchao Feng
 * @version 1.0
 * @see <a href="http://www.ietf.org/rfc/rfc1928.txt">SOCKS Protocol Version 5</a>
 */
public class GenericSocksMethodRequester implements SocksMethodRequester {

  /**
   * Logger that subclasses also can use.
   */
  private static final Logger logger = LoggerFactory.getLogger(GenericSocksMethodRequester.class);

  @Override
  public void doRequest(Socket socket) throws IOException {
    InputStream inputStream = socket.getInputStream();
    OutputStream outputStream = socket.getOutputStream();
    byte[] bufferSent = new byte[3];

    bufferSent[0] = (byte) 0x05;
    bufferSent[1] = (byte) 1;
    bufferSent[2] = (byte) 0x00;

    outputStream.write(bufferSent);
    outputStream.flush();

    logger.debug("{}", LogMessageBuilder.build(bufferSent, MsgType.SEND));

    // Received data.
    byte[] receivedData = readData(inputStream);
    logger.debug("{}", LogMessageBuilder.build(receivedData, MsgType.RECEIVE));

    if (receivedData[0] != 0x05) {
      throw new SocksException("Remote server don't support SOCKS5");
    }
  }

  private byte[] readData(InputStream inputStream)
          throws IOException {
    byte[] bytes = new byte[2];
    for (int i = 0; i < 2; i++) {
		int b = inputStream.read();
		if (b < 0) {
			throw new IOException("End of stream");
		}
		bytes[i] = (byte) b;
	}
    return bytes;
  }

}
