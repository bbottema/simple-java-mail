

package sockslib.utils;


public final class UnsignedByte {


  private byte num;

  public static int toInt(byte b) {
    return b & 0xFF;
  }


  public static String toHexString(byte b) {
    return Integer.toHexString(toInt(b));
  }

  public byte getSignedValue() {
    return num;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof UnsignedByte) {
      return num == ((UnsignedByte) obj).getSignedValue();
    }
    return false;
  }

  @Override
  public int hashCode() {
    return new Integer(num).hashCode();
  }

}
