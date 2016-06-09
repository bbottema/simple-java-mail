

package sockslib.common;

import java.io.Serializable;
import java.security.Principal;

import static sockslib.utils.Util.checkNotNull;

public class Socks5UserPrincipal implements Principal, Serializable {


  private String username;

  public Socks5UserPrincipal(String username) {
    this.username = checkNotNull(username, "Argument [username] may not be null");
  }

  @Override
  public String getName() {
    return this.username;
  }

}
