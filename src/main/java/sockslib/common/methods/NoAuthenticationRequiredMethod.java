

package sockslib.common.methods;

import sockslib.client.SocksProxy;

import java.io.IOException;



public class NoAuthenticationRequiredMethod extends AbstractSocksMethod {

  @Override
  public final int getByte() {
    return 0x00;
  }

  @Override
  public void doMethod(SocksProxy socksProxy) throws  IOException {
    // Do nothing.
  }

  @Override
  public String getMethodName() {
    return "NO Authentication Required";
  }

}
