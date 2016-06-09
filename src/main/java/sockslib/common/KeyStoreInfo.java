

package sockslib.common;

import static sockslib.utils.Util.checkNotNull;

public class KeyStoreInfo {

  private String keyStorePath;
  private String password;
  private String type = "JKS";

  public KeyStoreInfo() {
  }

  public KeyStoreInfo(String keyStorePath, String password, String type) {
    this.keyStorePath = checkNotNull(keyStorePath, "Argument [keyStorePath] may not be null");
    this.password = checkNotNull(password, "Argument [password] may not be null");
    this.type = checkNotNull(type, "Argument [type] may not be null");
  }

  public KeyStoreInfo(String keyStorePath, String password) {
    this(keyStorePath, password, "JKS");
  }

  public String getKeyStorePath() {
    return keyStorePath;
  }

  public String getPassword() {
    return password;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = checkNotNull(type);
  }

  @Override
  public String toString() {
    return "[KEY STORE] PATH:" + keyStorePath + " PASSWORD:" + password + " TYPE:" + type;
  }

}
