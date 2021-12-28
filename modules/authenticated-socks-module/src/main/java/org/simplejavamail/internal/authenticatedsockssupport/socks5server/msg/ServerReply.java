package org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg;

import org.jetbrains.annotations.Nullable;

/**
 * The enumeration <code>ServerReply</code> represents reply of servers when a SOCKS client sends a command request to the SOCKS server.
 */
public enum ServerReply {

	SUCCEEDED(0x00, null),

	GENERAL_SOCKS_SERVER_FAILURE(0x01, "General SOCKS server failure"),

	NETWORK_UNREACHABLE(0x03, "Network unreachable"),

	HOST_UNREACHABLE(0x04, "Host unreachable"),

	CONNECTION_REFUSED(0x05, "Connection refused"),

	TTL_EXPIRED(0x06, "TTL expired"),

	ADDRESS_TYPE_NOT_SUPPORTED(0x08, "Address type not supported");

	private final byte value;

	@Nullable
	private final String errorMessage;

	ServerReply(final int value, @Nullable final String errorMessage) {
		this.value = (byte) value;
		this.errorMessage = errorMessage;
	}

	public byte getValue() {
		return value;
	}

	@Nullable
	public String getErrorMessage() {
		return errorMessage;
	}
}