package testutil.socks.server.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testutil.socks.server.commons.Constants;
import testutil.socks.server.commons.Utils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class Socks4Impl implements SocksCommonInterface {

	private static final Logger LOGGER = LoggerFactory.getLogger(Socks4Impl.class);

	public byte SOCKS_Version = 0;

	public ProxyHandler m_Parent = null;

	public byte socksCommand;
	public byte DST_Port[] = null;
	public byte DST_Addr[] = null;
	public byte UserID[] = null;

	public String UID = "";


	//--- Reply Codes ---
	public byte getSuccessCode() {
		return 90;
	}

	public byte getFailCode() {
		return 91;
	}
	//-------------------

	protected InetAddress m_ServerIP = null;
	protected int m_nServerPort = 0;

	protected InetAddress m_ClientIP = null;
	protected int m_nClientPort = 0;

	public InetAddress getClientAddress() {
		return m_ClientIP;
	}

	public InetAddress getServerAddress() {
		return m_ServerIP;
	}

	public int getClientPort() {
		return m_nClientPort;
	}

	public int getServerPort() {
		return m_nServerPort;
	}

	public InetAddress m_ExtLocalIP = null;

	public String commName(byte code) {

		switch (code) {
			case 0x01:
				return "CONNECT";
			case 0x02:
				return "BIND";
			case 0x03:
				return "UDP Association";
			default:
				return "Unknown Command";
		}

	}
	/////////////////////////////////////////////////////////////////

	public String replyName(byte code) {

		switch (code) {
			case 0:
				return "SUCCESS";
			case 1:
				return "General SOCKS Server failure";
			case 2:
				return "Connection not allowed by ruleset";
			case 3:
				return "Network Unreachable";
			case 4:
				return "HOST Unreachable";
			case 5:
				return "Connection Refused";
			case 6:
				return "TTL Expired";
			case 7:
				return "Command not supported";
			case 8:
				return "Address Type not Supported";
			case 9:
				return "to 0xFF UnAssigned";

			case 90:
				return "Request GRANTED";
			case 91:
				return "Request REJECTED or FAILED";
			case 92:
				return "Request REJECTED - SOCKS server can't connect to Identd on the client";
			case 93:
				return "Request REJECTED - Client and Identd report diff user-ID";

			default:
				return "Unknown Command";
		}
	}
	/////////////////////////////////////////////////////////////////

	public Socks4Impl(ProxyHandler Parent) {

		m_Parent = Parent;

		DST_Addr = new byte[4];
		DST_Port = new byte[2];
	}

	/////////////////////////////////////////////////////////////////

	public void calculateUserID() {

		String s = UID + " ";
		UserID = s.getBytes();
		UserID[UserID.length - 1] = 0x00;
	}


	public boolean calculateAddress() {

		// IP v4 Address Type
		m_ServerIP = Utils.calcInetAddress(DST_Addr);
		m_nServerPort = Utils.calcPort(DST_Port[0], DST_Port[1]);

		m_ClientIP = m_Parent.m_ClientSocket.getInetAddress();
		m_nClientPort = m_Parent.m_ClientSocket.getPort();

		return ((m_ServerIP != null) && (m_nServerPort >= 0));
	}
	/////////////////////////////////////////////////////////////////	

	protected byte getByte() {
		byte b;
		try {
			b = m_Parent.getByteFromClient();
		} catch (Exception e) {
			b = 0;
		}
		return b;
	}
	/////////////////////////////////////////////////////////////

	public void authenticate(byte SOCKS_Ver)
			throws Exception {

		SOCKS_Version = SOCKS_Ver;
	}

	/////////////////////////////////////////////////////////////

	public void getClientCommand() throws Exception {
		byte b;

		// Version was get in method Authenticate()
		socksCommand = getByte();

		DST_Port[0] = getByte();
		DST_Port[1] = getByte();

		for (int i = 0; i < 4; i++) {
			DST_Addr[i] = getByte();
		}

		while ((b = getByte()) != 0x00) {
			UID += (char) b;
		}
		calculateUserID();

		if ((socksCommand < Constants.SC_CONNECT) || (socksCommand > Constants.SC_BIND)) {
			refuseCommand((byte) 91);
			throw new Exception("Socks 4 - Unsupported Command : " + commName(socksCommand));
		}

		if (!calculateAddress()) {  // Gets the IP Address
			refuseCommand((byte) 92);    // Host Not Exists...
			throw new Exception("Socks 4 - Unknown Host/IP address '" + m_ServerIP.toString());
		}

		LOGGER.debug(("Accepted SOCKS 4 Command: \"" + commName(socksCommand) + "\"") + Constants.EOL);
	}  // GetClientCommand()
	/////////////////////////////////////////////////////////////

	public void replyCommand(byte ReplyCode) {
		LOGGER.debug(("Socks 4 reply: \"" + replyName(ReplyCode) + "\"") + Constants.EOL);

		byte[] REPLY = new byte[8];
		REPLY[0] = 0;
		REPLY[1] = ReplyCode;
		REPLY[2] = DST_Port[0];
		REPLY[3] = DST_Port[1];
		REPLY[4] = DST_Addr[0];
		REPLY[5] = DST_Addr[1];
		REPLY[6] = DST_Addr[2];
		REPLY[7] = DST_Addr[3];

		m_Parent.sendToClient(REPLY);
	}

	protected void refuseCommand(byte errorCode) {
		LOGGER.debug(("Socks 4 - Refuse Command: \"" + replyName(errorCode) + "\"") + Constants.EOL);
		replyCommand(errorCode);
	}    // Refuse_Command()

	/////////////////////////////////////////////////////////////

	public void connect() throws Exception {

		LOGGER.debug("Connecting..." + Constants.EOL);
		//	Connect to the Remote Host
		try {
			m_Parent.connectToServer(m_ServerIP.getHostAddress(), m_nServerPort);
		} catch (IOException e) {
			refuseCommand(getFailCode()); // Connection Refused
			throw new Exception("Socks 4 - Can't connect to " +
					Utils.getSocketInfo(m_Parent.m_ServerSocket));
		}

		LOGGER.debug(("Connected to " + Utils.getSocketInfo(m_Parent.m_ServerSocket)) + Constants.EOL);
		replyCommand(getSuccessCode());
	}

	public void bindReply(byte ReplyCode, InetAddress IA, int PT) throws IOException {
		byte IP[] = {0, 0, 0, 0};

		LOGGER.debug(("Reply to Client : \"" + replyName(ReplyCode) + "\"") + Constants.EOL);

		byte[] REPLY = new byte[8];
		if (IA != null) IP = IA.getAddress();

		REPLY[0] = 0;
		REPLY[1] = ReplyCode;
		REPLY[2] = (byte) ((PT & 0xFF00) >> 8);
		REPLY[3] = (byte) (PT & 0x00FF);
		REPLY[4] = IP[0];
		REPLY[5] = IP[1];
		REPLY[6] = IP[2];
		REPLY[7] = IP[3];

		if (m_Parent.isActive()) {
			m_Parent.sendToClient(REPLY);
		} else {
			LOGGER.debug("Closed BIND Client Connection" + Constants.EOL);
		}
	} // Reply_Command()

	/////////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////////
	//	It is IMPOSSIBLE to resolve normally the External
	//	IP address of yout machine )-: !!!
	/////////////////////////////////////////////////////////////
	public InetAddress resolveExternalLocalIP() {

		InetAddress IP = null;

		if (m_ExtLocalIP != null) {
			Socket sct = null;
			try {
				sct = new Socket(m_ExtLocalIP, m_Parent.getSocksServer().getPort());
				IP = sct.getLocalAddress();
				sct.close();
				return m_ExtLocalIP;
			} catch (IOException e) {
				LOGGER.debug("WARNING !!! THE LOCAL IP ADDRESS WAS CHANGED !" + Constants.EOL);
			}
		}

		String[] hosts = {"www.sun.com", "www.microsoft.com",
				"www.aol.com", "www.altavista.com",
				"www.mirabilis.com", "www.yahoo.com"};

		for (int i = 0; i < hosts.length; i++) {
			try {
				Socket sct = new Socket(InetAddress.getByName(hosts[i]), 80);
				IP = sct.getLocalAddress();
				sct.close();
				break;
			} catch (Exception e) {  // IP == null
				LOGGER.debug(("Error in BIND() - BIND reip Failed at " + i) + Constants.EOL);
			}
		}

		m_ExtLocalIP = IP;
		return IP;
	}

	/////////////////////////////////////////////////////////////

	public void bind() throws IOException {
		ServerSocket ssock = null;
		InetAddress MyIP = null;
		int MyPort = 0;

		LOGGER.debug("Binding..." + Constants.EOL);
		// Resolve External IP
		MyIP = resolveExternalLocalIP();

		LOGGER.debug(("Local IP : " + MyIP.toString()) + Constants.EOL);


		try {
			ssock = new ServerSocket(0);
			ssock.setSoTimeout(Constants.DEFAULT_PROXY_TIMEOUT);
			MyPort = ssock.getLocalPort();
		} catch (IOException e) {  // MyIP == null
			LOGGER.debug("Error in BIND() - Can't BIND at any Port" + Constants.EOL);
			bindReply((byte) 92, MyIP, MyPort);
			ssock.close();
			return;
		}

		LOGGER.debug(("BIND at : <" + MyIP.toString() + ":" + MyPort + ">") + Constants.EOL);
		bindReply((byte) 90, MyIP, MyPort);

		Socket socket = null;

		while (socket == null) {
			if (m_Parent.checkClientData() >= 0) {
				LOGGER.debug("BIND - Client connection closed" + Constants.EOL);
				ssock.close();
				return;
			}

			try {
				socket = ssock.accept();
				socket.setSoTimeout(Constants.DEFAULT_PROXY_TIMEOUT);
			} catch (InterruptedIOException e) {
				socket.close();
			}
			Thread.yield();
		}
		
		
/*		if( socket.getInetAddress() != m_m_ServerIP )	{
			BIND_Reply( (byte)91,	socket.getInetAddress(), 
									socket.getPort() );
			Log.Warning( m_Server, "BIND Accepts different IP/P" );
			m_Server.Close();
			return;
		}
*/

		m_ServerIP = socket.getInetAddress();
		m_nServerPort = socket.getPort();

		bindReply((byte) 90, socket.getInetAddress(),
				socket.getPort());

		m_Parent.m_ServerSocket = socket;
		m_Parent.prepareServer();

		LOGGER.debug(("BIND Connection from " + Utils.getSocketInfo(m_Parent.m_ServerSocket)) + Constants.EOL);
		ssock.close();


	}// BIND...
	/////////////////////////////////////////////////////////////

	public void udp() throws IOException {
		LOGGER.debug("Error - Socks 4 don't support UDP Association!" + Constants.EOL);
		LOGGER.debug("Check your Software please..." + Constants.EOL);
		refuseCommand((byte) 91);    // SOCKS4 don't support UDP
	}
	/////////////////////////////////////////////////////////////
}
/////////////////////////////////////////////////////////////////