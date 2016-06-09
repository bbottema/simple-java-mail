

package sockslib.common.methods;

import sockslib.client.Socks5;

import java.io.IOException;

public interface SocksMethod {

	int getByte();

	String getMethodName();

	void doMethod(Socks5 socksProxy)
			throws IOException;

}
