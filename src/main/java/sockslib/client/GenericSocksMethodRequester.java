

package sockslib.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sockslib.common.SocksException;
import sockslib.common.methods.SocksMethod;
import sockslib.common.methods.SocksMethodRegistry;
import sockslib.utils.LogMessageBuilder;
import sockslib.utils.LogMessageBuilder.MsgType;
import sockslib.utils.StreamUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;


public class GenericSocksMethodRequester implements SocksMethodRequester {


  protected static final Logger logger = LoggerFactory.getLogger(GenericSocksMethodRequester.class);

  @Override
  public SocksMethod doRequest(List<SocksMethod> acceptableMethods, Socket socket, int
      socksVersion) throws SocksException, IOException {
    InputStream inputStream = socket.getInputStream();
    OutputStream outputStream = socket.getOutputStream();
    byte[] bufferSent = new byte[2 + acceptableMethods.size()];

    bufferSent[0] = (byte) socksVersion;
    bufferSent[1] = (byte) acceptableMethods.size();
    for (int i = 0; i < acceptableMethods.size(); i++) {
      bufferSent[2 + i] = (byte) acceptableMethods.get(i).getByte();
    }

    outputStream.write(bufferSent);
    outputStream.flush();

    logger.debug("{}", LogMessageBuilder.build(bufferSent, MsgType.SEND));

    // Received data.
    byte[] receivedData = StreamUtil.read(inputStream, 2);
    logger.debug("{}", LogMessageBuilder.build(receivedData, MsgType.RECEIVE));

    if (receivedData[0] != socksVersion) {
      throw new SocksException("Remote server don't support SOCKS5");
    }

    return SocksMethodRegistry.getByByte(receivedData[1]);
  }

}
