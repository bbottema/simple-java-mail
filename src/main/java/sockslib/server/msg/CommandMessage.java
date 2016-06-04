package sockslib.server.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sockslib.common.AddressType;
import sockslib.common.SocksCommand;
import sockslib.common.SocksException;
import sockslib.utils.SocksUtil;
import sockslib.utils.StreamUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;

import static sockslib.utils.StreamUtil.checkEnd;

/**
 * The class <code>RequestCommandMessage</code> represents a SOCKS5 command message.
 */
public class CommandMessage implements ReadableMessage, WritableMessage {

	private static final Logger logger = LoggerFactory.getLogger(CommandMessage.class);

	private static final int CMD_CONNECT = 0x01;

	private static final int CMD_BIND = 0x02;

	private static final int RESERVED = 0x00;

	private int version;

	private InetAddress inetAddress;

	private int port;

	private String host;

	private SocksCommand command;

	private int addressType;

	private SocksException socksException;

	@Override
	public byte[] getBytes() {
		logger.trace("CommandMessage.getBytes");

		final byte[] bytes;

		switch (addressType) {
		case AddressType.IPV4:
			bytes = new byte[10];
			byte[] ipv4Bytes = inetAddress.getAddress();// todo
			System.arraycopy(ipv4Bytes, 0, bytes, 4, ipv4Bytes.length);
			bytes[8] = SocksUtil.getFirstByteFromInt(port);
			bytes[9] = SocksUtil.getSecondByteFromInt(port);
			break;

		case AddressType.IPV6:
			bytes = new byte[22];
			byte[] ipv6Bytes = inetAddress.getAddress();// todo
			System.arraycopy(ipv6Bytes, 0, bytes, 4, ipv6Bytes.length);
			bytes[20] = SocksUtil.getFirstByteFromInt(port);
			bytes[21] = SocksUtil.getSecondByteFromInt(port);
			break;

		case AddressType.DOMAIN_NAME:
			final int hostLength = host.getBytes().length;
			bytes = new byte[7 + hostLength];
			bytes[4] = (byte) hostLength;
			for (int i = 0; i < hostLength; i++) {
				bytes[5 + i] = host.getBytes()[i];
			}
			bytes[5 + hostLength] = SocksUtil.getFirstByteFromInt(port);
			bytes[6 + hostLength] = SocksUtil.getSecondByteFromInt(port);
			break;
		default:
			throw new RuntimeException("unknown type of address: " + addressType);
		}

		bytes[0] = (byte) version;
		bytes[1] = (byte) command.getValue();
		bytes[2] = RESERVED;
		bytes[3] = (byte) addressType;

		return bytes;
	}

	@Override
	public void read(InputStream inputStream)
			throws IOException {
		logger.trace("CommandMessage.read");

		version = checkEnd(inputStream.read());
		int cmd = checkEnd(inputStream.read());

		switch (cmd) {
		case CMD_CONNECT:
			command = SocksCommand.CONNECT;
			break;
		case CMD_BIND:
			command = SocksCommand.BIND;
			break;

		default:
			socksException = SocksException.serverReplyException(ServerReply.COMMAND_NOT_SUPPORTED);
		}
		checkEnd(inputStream.read());
		addressType = checkEnd(inputStream.read());

		if (!AddressType.isSupport(addressType) && socksException == null) {
			socksException = SocksException.serverReplyException(ServerReply.ADDRESS_TYPE_NOT_SUPPORTED);
		}

		// read address
		switch (addressType) {

		case AddressType.IPV4:
			byte[] addressBytes = StreamUtil.read(inputStream, 4);
			inetAddress = InetAddress.getByAddress(addressBytes);
			break;

		case AddressType.DOMAIN_NAME:
			int domainLength = checkEnd(inputStream.read());
			if (domainLength < 1) {
				throw new SocksException("Length of domain must great than 0");
			}
			byte[] domainBytes = StreamUtil.read(inputStream, domainLength);
			host = new String(domainBytes, Charset.forName("UTF-8"));
			try {
				inetAddress = InetAddress.getByName(host);
			} catch (UnknownHostException e) {
				if (socksException == null) {
					socksException = SocksException.serverReplyException(ServerReply.HOST_UNREACHABLE);
				}
			}
			break;
		default:
			// TODO Implement later.
			break;
		}

		// Read port
		byte[] portBytes = StreamUtil.read(inputStream, 2);
		port = SocksUtil.bytesToInt(portBytes);

	}

	public boolean hasSocksException() {
		return socksException != null;
	}

	public InetAddress getInetAddress() {
		return inetAddress;
	}

	public int getPort() {
		return port;
	}

	public SocksCommand getCommand() {
		return command;
	}

	public SocksException getSocksException() {
		return socksException;
	}

}
