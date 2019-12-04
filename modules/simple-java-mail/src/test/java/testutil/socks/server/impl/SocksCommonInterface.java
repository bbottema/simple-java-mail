package testutil.socks.server.impl;

import java.io.IOException;
import java.net.InetAddress;

public interface SocksCommonInterface {
	
	void    authenticate(byte SOCKS_Ver) throws	Exception;
	void    bind()	throws IOException;
	void	bindReply(byte ReplyCode, InetAddress IA, int PT);
	void	connect() throws Exception;
	void	replyCommand(byte replyCode);
	byte	getSuccessCode();
	byte	getFailCode();
	void	getClientCommand() throws Exception;
}
