package sockslib.common.methods;

import sockslib.client.SocksProxy;
import sockslib.common.NotImplementException;
import sockslib.common.SocksException;

public class GssApiMethod extends AbstractSocksMethod {

	@Override
	public final int getByte() {
		return 0x01;
	}

	@Override
	public void doMethod(SocksProxy socksProxy)
			throws SocksException {
		// TODO implements later.
		throw new NotImplementException();
	}

	@Override
	public String getMethodName() {
		return "GSS API";
	}

}
