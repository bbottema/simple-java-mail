package testutil.socks.server.commons;

public interface Constants {
	
	
	public static final int LISTEN_PORT = 8888; 
	public static final String PROXY_HOST = "127.0.0.1";
	public static final int PROXY_PORT = -1;
	
	public	static	final int LISTEN_TIMEOUT	= 200;
	public	static	final int DEFAULT_SERVER_TIMEOUT	= 200;
	public static final int	DEFAULT_BUF_SIZE = 4096;
	public static final int DEFAULT_PROXY_TIMEOUT	= 10;
	
	public static	final	byte	SOCKS5_Version	= 0x05;
	public static	final	byte	SOCKS4_Version	= 0x04;

	public	static	final	String	EOL = "\r\n";
	
	public static	byte	SRE_Refuse[] = { (byte)0x05, (byte)0xFF };
	public static	byte	SRE_Accept[] = { (byte)0x05, (byte)0x00 };
	
	
	
	public static	final	byte	SC_CONNECT	= 0x01;
	public static	final	byte	SC_BIND		= 0x02;
	public static	final	byte	SC_UDP		= 0x03;	
	
	public static	final	int		MAX_ADDR_LEN	= 255;
}
