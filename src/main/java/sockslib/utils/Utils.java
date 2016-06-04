package sockslib.utils;

public class Utils {

	public static class Assert {
		public static <T> T checkNotNull(T arg, String msg) {
			if (arg == null) {
				throw new NullPointerException(msg);
			}
			return arg;
		}
	}

}
