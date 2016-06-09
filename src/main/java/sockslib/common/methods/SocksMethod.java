

package sockslib.common.methods;

import sockslib.client.SocksProxy;

import java.io.IOException;



public interface SocksMethod {


  int getByte();


  String getMethodName();


  void doMethod(SocksProxy socksProxy) throws IOException;

}
