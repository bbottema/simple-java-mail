

package sockslib.common.methods;



public abstract class AbstractSocksMethod implements SocksMethod {

  @Override
  public int hashCode() {
    return new Integer(getByte()).hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof SocksMethod && ((SocksMethod) obj).getByte() == this.getByte();
  }

}
