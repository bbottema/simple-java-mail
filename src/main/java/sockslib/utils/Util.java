package sockslib.utils;

import static java.lang.Integer.toHexString;

public class Util {

	public static <T> T checkNotNull(T value, String msg) {
		if (value == null) {
			throw new NullPointerException(msg);
		}
		return value;
	}

	public static String buildLogString(byte[] bytes, boolean isReceived) {
		StringBuilder debugMsg = new StringBuilder();
		debugMsg.append(isReceived ? "Received: " : "Sent: ");
		for (byte aByte : bytes) {
			debugMsg.append(toHexString(toInt(aByte))).append(" ");
		}
		return debugMsg.toString();
	}

	public static int toInt(byte b) {
		return b & 0xFF;
	}
}
