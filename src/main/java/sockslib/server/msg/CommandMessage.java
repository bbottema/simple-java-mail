package sockslib.server.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sockslib.common.AddressType;
import sockslib.common.SocksCommand;
import sockslib.common.SocksException;
import sockslib.utils.StreamUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;

import static sockslib.utils.StreamUtil.checkEnd;

public class CommandMessage {

	private static final Logger logger = LoggerFactory.getLogger(CommandMessage.class);

	private InetAddress inetAddress;

	private int port;

	private SocksCommand command;

	private SocksException socksException;

	public void read(InputStream inputStream)
			throws IOException {
		logger.trace("CommandMessage.read");

		checkEnd(inputStream.read()); // version, unused
		int cmd = checkEnd(inputStream.read());

		if ((command = SocksCommand.fromCmd(cmd)) == null) {
			socksException = new SocksException(ServerReply.COMMAND_NOT_SUPPORTED);
		}

		checkEnd(inputStream.read());
		int addressType = checkEnd(inputStream.read());

		if (!AddressType.isSupport(addressType) && socksException == null) {
			socksException = new SocksException(ServerReply.ADDRESS_TYPE_NOT_SUPPORTED);
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
				String host = new String(domainBytes, Charset.forName("UTF-8"));
				try {
					inetAddress = InetAddress.getByName(host);
				} catch (UnknownHostException e) {
					if (socksException == null) {
						socksException = new SocksException(ServerReply.HOST_UNREACHABLE);
					}
				}
				break;
			default:
				// TODO Implement later.
				break;
		}

		port = bytesToInt(StreamUtil.read(inputStream, 2));
	}

	private int bytesToInt(byte[] portBytes) {
		if (portBytes.length != 2) {
			throw new IllegalArgumentException("byte array size must be 2");
		}
		return ((portBytes[0] & 0xFF) << 8) | portBytes[1] & 0xFF;
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
