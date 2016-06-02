package sockslib.server.msg;

import java.io.IOException;
import java.io.InputStream;

public interface ReadableMessage {

  void read(InputStream inputStream) throws IOException;

}
