package sockslib.server.msg;

/**
 * The class <code>MethodSelectionResponseMessage</code> represents response message for method
 * selection message. This message is always sent by SOCKS server.
 */
public class MethodSelectionResponseMessage implements WritableMessage {

	@Override
	public byte[] getBytes() {
		byte[] bytes = new byte[2];
		bytes[0] = (byte) 0x5;
		bytes[1] = (byte) 0x00;
		return bytes;
	}

}
