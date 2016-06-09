

package sockslib.common;

import java.security.Principal;

import static sockslib.utils.Util.checkNotNull;

public class UsernamePasswordCredentials implements Credentials {

  private Socks5UserPrincipal principal;

  private String password;

  public UsernamePasswordCredentials(String username, String password) {
    this.principal = new Socks5UserPrincipal(checkNotNull(username, "Username may not be null"));
    this.password = checkNotNull(password, "Argument [password] may not be null");
  }

  @Override
  public Principal getUserPrincipal() {
    return this.principal;
  }

  @Override
  public String getPassword() {
    return this.password;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj instanceof UsernamePasswordCredentials) {
      final UsernamePasswordCredentials that = (UsernamePasswordCredentials) obj;
      if (this.principal.equals(that.principal)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int hashCode() {
    return this.principal.hashCode();
  }

  @Override
  public String toString() {
    return this.principal.toString();
  }

}
