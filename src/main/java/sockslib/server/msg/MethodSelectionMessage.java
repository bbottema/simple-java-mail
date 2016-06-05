package sockslib.server.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static sockslib.utils.StreamUtil.checkEnd;

public class MethodSelectionMessage {

	private static final Logger logger = LoggerFactory.getLogger(MethodSelectionMessage.class);

	private int version;

	public void read(InputStream inputStream)
			throws IOException {
		logger.trace("MethodSelectionMessage.read");
		version = checkEnd(inputStream.read());
		int methodNum = checkEnd(inputStream.read());
		for (int i = 0; i < methodNum; i++) {
			checkEnd(inputStream.read()); // read method byte
		}
	}

	public int getVersion() {
		return version;
	}
}
