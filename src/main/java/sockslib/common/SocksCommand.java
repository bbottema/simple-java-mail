

package sockslib.common;


public enum SocksCommand {


  CONNECT(0x01),

  BIND(0x02),


  UDP_ASSOCIATE(0x03);


  private int value;


  SocksCommand(int value) {
    this.value = value;
  }


  public int getValue() {
    return value;
  }
}
