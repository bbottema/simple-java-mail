package testutil.socks.server.commons;

public interface Constants {

	int LISTEN_PORT = 8888;
	String PROXY_HOST = "127.0.0.1";
	int PROXY_PORT = -1;

	int LISTEN_TIMEOUT = 200;
	int DEFAULT_SERVER_TIMEOUT = 200;
	int DEFAULT_BUF_SIZE = 4096;
	int DEFAULT_PROXY_TIMEOUT = 10;

	byte SOCKS5_Version = 0x05;
	byte SOCKS4_Version = 0x04;

	byte[] SRE_Refuse = {(byte) 0x05, (byte) 0xFF};
	byte[] SRE_Accept = {(byte) 0x05, (byte) 0x00};

	byte SC_CONNECT = 0x01;
	byte SC_BIND = 0x02;
	byte SC_UDP = 0x03;

	int MAX_ADDR_LEN = 255;
}