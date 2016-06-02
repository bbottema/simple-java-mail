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
		// bytes to int
		return ((bytes[0] & 0xFF) << 8) | bytes[1] & 0xFF;
	}

}
