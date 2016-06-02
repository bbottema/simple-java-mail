package sockslib.common;

/**
 * The enumeration <code>SocksCommand</code> represents SOCKS command.<br>
 * SOCKS4 protocol support CONNECT and BIND, SOCKS5 protocol supports CONNECT, BIND, and UDP
 * ASSOCIATE.
 */
public enum SocksCommand {

  /**
   * Supported by SOCKS4 and SOCKS5 protocol.
   */
  CONNECT(0x01),
  /**
   * Supported by SOCKS4 and SOCKS5 protocol.
   */
  BIND(0x02);

  /**
   * the unsigned byte that represents the command.
   */
  private final int value;

  SocksCommand(int value) {
    this.value = value;
  }

  public int getValue() {
    return value;
  }
}
