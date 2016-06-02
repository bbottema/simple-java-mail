package sockslib.server.msg;

/**
 * The enumeration <code>ServerReply</code> represents reply of servers will SOCKS client send a
 * command request to the SOCKS server.
 */
public enum ServerReply {

  SUCCEEDED(0x00),

  GENERAL_SOCKS_SERVER_FAILURE(0x01),

  NETWORK_UNREACHABLE(0x03),

  HOST_UNREACHABLE(0x04),

  CONNECTION_REFUSED(0x05),

  TTL_EXPIRED(0x06),

  COMMAND_NOT_SUPPORTED(0x07),

  ADDRESS_TYPE_NOT_SUPPORTED(0x08);

  private final byte value;

  ServerReply(int value) {
    this.value = (byte) value;
  }

  public byte getValue() {
    return value;
  }

}
