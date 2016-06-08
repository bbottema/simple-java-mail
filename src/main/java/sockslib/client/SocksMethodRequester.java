package sockslib.client;

import sockslib.common.methods.SocksMethod;

import java.io.IOException;
import java.net.Socket;
import java.util.List;

interface SocksMethodRequester {

	SocksMethod doRequest(List<SocksMethod> acceptableMethods, Socket socket, int socksVersion)
			throws IOException;
}
