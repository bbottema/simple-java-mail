

package sockslib.common;

import java.security.Principal;


public class AnonymousCredentials implements Credentials {

  public AnonymousCredentials() {
  }

  @Override
  public Principal getUserPrincipal() {
    return null;
  }

  @Override
  public String getPassword() {
    return null;
  }

}
