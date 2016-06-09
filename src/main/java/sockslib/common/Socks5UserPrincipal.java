

package sockslib.common;

import java.io.Serializable;
import java.security.Principal;

import static sockslib.utils.Util.checkNotNull;

public class Socks5UserPrincipal implements Principal, Serializable {


  private static final long serialVersionUID = 1L;
  private String username;

  public Socks5UserPrincipal(String username) {
    this.username = checkNotNull(username, "Argument [username] may not be null");
  }

  @Override
  public String getName() {
    return this.username;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj instanceof Socks5UserPrincipal) {
      final Socks5UserPrincipal that = (Socks5UserPrincipal) obj;
      if (this.username.equals(that.username)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("Principal[%s]", this.username);
  }

}
