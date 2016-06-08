package sockslib.common;

public class AuthenticationException extends SocksException {

	public AuthenticationException() {
		super("Username or password error");
	}

}
