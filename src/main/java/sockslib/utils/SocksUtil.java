package sockslib.utils;

public class SocksUtil {

	public static byte getFirstByteFromInt(int num) {
		return (byte) ((num & 0xff00) >> 8);
	}

	public static byte getSecondByteFromInt(int num) {
		return (byte) (num & 0xff);
	}

}