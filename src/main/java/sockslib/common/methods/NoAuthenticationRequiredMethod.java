

package sockslib.common.methods;

import sockslib.client.Socks5;

import java.io.IOException;

public class NoAuthenticationRequiredMethod extends AbstractSocksMethod {

	@Override
	public final int getByte() {
		return 0x00;
	}

	@Override
	public void doMethod(Socks5 socksProxy)
			throws IOException {
		// Do nothing.
	}

	@Override
	public String getMethodName() {
		return "NO Authentication Required";
	}

}
