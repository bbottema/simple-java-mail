package sockslib.server.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sockslib.common.AddressType;
import sockslib.utils.SocksUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class CommandResponseMessage implements WritableMessage {

	private static final Logger logger = LoggerFactory.getLogger(CommandResponseMessage.class);

	private int version = 5;

	private int addressType = AddressType.IPV4;

	private InetAddress bindAddress;

	private int bindPort;

	private final ServerReply reply;

	public CommandResponseMessage(ServerReply reply) {
		byte[] defaultAddress = { 0, 0, 0, 0 };
		this.reply = reply;
		try {
			bindAddress = InetAddress.getByAddress(defaultAddress);
			addressType = 0x01;
		} catch (UnknownHostException e) {
			logger.error(e.getMessage(), e);
		}
	}

	public CommandResponseMessage(ServerReply reply, InetAddress bindAddress, int bindPort) {
		version = sockslib.server.Socks5Handler.VERSION;
		this.reply = reply;
		this.bindAddress = bindAddress;
		this.bindPort = bindPort;
		if (bindAddress.getAddress().length == 4) {
			addressType = 0x01;
		} else {
			addressType = 0x04;
		}
	}

	@Override
	public byte[] getBytes() {
		logger.trace("CommandResponseMessage.getBytes");
		final byte[] bytes;

		switch (addressType) {
		case AddressType.IPV4:
			bytes = new byte[10];
			for (int i = 0; i < bindAddress.getAddress().length; i++) {
				bytes[i + 4] = bindAddress.getAddress()[i];
			}
			bytes[8] = SocksUtil.getFirstByteFromInt(bindPort);
			bytes[9] = SocksUtil.getSecondByteFromInt(bindPort);
			break;
		case AddressType.IPV6:
			bytes = new byte[22];
			for (int i = 0; i < bindAddress.getAddress().length; i++) {
				bytes[i + 4] = bindAddress.getAddress()[i];
			}
			bytes[20] = SocksUtil.getFirstByteFromInt(bindPort);
			bytes[21] = SocksUtil.getSecondByteFromInt(bindPort);
			break;
		case AddressType.DOMAIN_NAME:
			throw new RuntimeException("Not implemented!");
		default:
			throw new RuntimeException("unknown type of address: " + addressType);
		}

		bytes[0] = (byte) version;
		bytes[1] = reply.getValue();
		bytes[2] = (byte) 0x00;
		bytes[3] = (byte) addressType;

		return bytes;
	}

}
