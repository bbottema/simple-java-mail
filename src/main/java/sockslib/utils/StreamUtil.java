

package sockslib.utils;

import java.io.IOException;
import java.io.InputStream;

public class StreamUtil {

	private static int checkEnd(int b)
			throws IOException {
		if (b < 0) {
			throw new IOException("End of stream");
		} else {
			return b;
		}
	}

	public static byte[] read(InputStream inputStream, int length)
			throws IOException {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) {
			bytes[i] = (byte) checkEnd(inputStream.read());
		}
		return bytes;
	}

}
