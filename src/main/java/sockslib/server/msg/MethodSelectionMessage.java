package sockslib.server.msg;

import java.io.IOException;
import java.io.InputStream;

import static sockslib.utils.StreamUtil.checkEnd;

public class MethodSelectionMessage implements ReadableMessage, WritableMessage {

  private int version;

  private int methodNum;

  private int[] methods;

  @Override
  public byte[] getBytes() {
    byte[] bytes = new byte[2 + methodNum];

    bytes[0] = (byte) version;
    bytes[1] = (byte) methodNum;
    for (int i = 0; i < methods.length; i++) {
      bytes[i + 2] = (byte) methods[i];
    }
    return bytes;
  }

  @Override
  public void read(InputStream inputStream) throws IOException {
    version = checkEnd(inputStream.read());
    methodNum = checkEnd(inputStream.read());
    methods = new int[methodNum];
    for (int i = 0; i < methodNum; i++) {
      methods[i] = checkEnd(inputStream.read());
    }
  }

  public int getVersion() {
    return version;
  }

}
