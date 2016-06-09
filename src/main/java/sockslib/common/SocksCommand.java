

package sockslib.common;

public enum SocksCommand {

	CONNECT(0x01);

	private final int value;

	SocksCommand(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}
}
