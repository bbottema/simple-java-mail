

package sockslib.common;

import java.security.Principal;


public interface Credentials {


  Principal getUserPrincipal();


  String getPassword();

}
