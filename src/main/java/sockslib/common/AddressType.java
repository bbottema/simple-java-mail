package sockslib.common;

public class AddressType {

  public static final int IPV4 = 0x01;

  public static final int DOMAIN_NAME = 0x03;

  public static final int IPV6 = 0x04;

  private AddressType() {
  }

  /**
   * Return <code>true</code> if type is supported.
   */
  public static boolean isSupport(int type) {
    return type == IPV4 || type == DOMAIN_NAME || type == IPV6;
  }

}
