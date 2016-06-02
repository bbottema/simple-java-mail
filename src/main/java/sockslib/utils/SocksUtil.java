package sockslib.utils;

public class SocksUtil {

	public static byte getFirstByteFromInt(int num) {
		return (byte) ((num & 0xff00) >> 8);
	}

	public static byte getSecondByteFromInt(int num) {
		return (byte) (num & 0xff);
	}

	public static int bytesToInt(byte[] bytes) {
		if (bytes.length != 2) {
			throw new IllegalArgumentException("byte array size must be 2");
		}
		return bytesToInt(bytes[0], bytes[1]);
	}

	private static int bytesToInt(byte b1, byte b2) {
		return ((b1 & 0xFF) << 8) | b2 & 0xFF;
	}
}
