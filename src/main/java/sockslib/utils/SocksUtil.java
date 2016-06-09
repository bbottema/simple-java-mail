

package sockslib.utils;


public class SocksUtil {

  public static int bytesToInt(byte b1, byte b2) {
    return (UnsignedByte.toInt(b1) << 8) | UnsignedByte.toInt(b2);
  }

}
