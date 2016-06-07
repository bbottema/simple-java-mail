package sockslib.utils;

/**
 * Created by Plantface on 7-6-2016.
 */
public class Util {
	public static void checkArgument(boolean value, String msg) {
		if (!value) {
			throw new IllegalArgumentException(msg);
		}
	}

	public static <T> T checkNotNull(T value) {
		if (value == null) {
			throw new NullPointerException();
		}
		return value;
	}

	public static <T> T checkNotNull(T value, String msg) {
		if (value == null) {
			throw new NullPointerException(msg);
		}
		return value;
	}

	public static boolean isNullOrEmpty(String value) {
		return (value == null || value.length() == 0);
	}
}
