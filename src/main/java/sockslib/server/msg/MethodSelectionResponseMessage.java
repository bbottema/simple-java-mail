package sockslib.server.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class <code>MethodSelectionResponseMessage</code> represents response message for method
 * selection message. This message is always sent by SOCKS server.
 */
public class MethodSelectionResponseMessage implements WritableMessage {

	private static final Logger logger = LoggerFactory.getLogger(MethodSelectionResponseMessage.class);

	@Override
	public byte[] getBytes() {
		logger.trace("MethodSelectionResponseMessage.getBytes");
		byte[] bytes = new byte[2];
		bytes[0] = (byte) 0x5;
		bytes[1] = (byte) 0x00;
		return bytes;
	}

}
