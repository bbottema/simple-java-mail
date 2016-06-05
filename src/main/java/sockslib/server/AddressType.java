package sockslib.server;

public class AddressType {

	public static final int IPV4 = 0x01;

	public static final int DOMAIN_NAME = 0x03;

	private AddressType() {
	}

	public static boolean isSupport(int type) {
		return type == IPV4 || type == DOMAIN_NAME;
	}
}