

package sockslib.common.methods;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sockslib.client.Socks5;
import sockslib.client.SocksProxy;
import sockslib.common.AuthenticationException;
import sockslib.common.SocksException;
import sockslib.common.ProxyCredentials;
import sockslib.utils.LogMessageBuilder;
import sockslib.utils.LogMessageBuilder.MsgType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static sockslib.utils.Util.checkNotNull;


public class UsernamePasswordMethod extends AbstractSocksMethod {


  private static final Logger logger = LoggerFactory.getLogger(UsernamePasswordMethod.class);


  public UsernamePasswordMethod() {
  }

  @Override
  public final int getByte() {
    return 0x02;
  }


  @Override
  public void doMethod(SocksProxy socksProxy) throws  IOException {
    checkNotNull(socksProxy, "Argument [socksProxy] may not be null");
    ProxyCredentials credentials = socksProxy.getCredentials();
    if (credentials == null || !(credentials instanceof ProxyCredentials)) {
      throw new SocksException("Need Username/Password authentication");
    }
    // UsernamePasswordAuthentication authentication = (UsernamePasswordAuthentication) auth;

    String username = credentials.getUsername();
    String password = credentials.getPassword();
    InputStream inputStream = socksProxy.getInputStream();
    OutputStream outputStream = socksProxy.getOutputStream();

    final int USERNAME_LENGTH = username.getBytes().length;
    final int PASSWORD_LENGTH = password.getBytes().length;
    final byte[] bytesOfUsername = username.getBytes();
    final byte[] bytesOfPassword = password.getBytes();
    final byte[] bufferSent = new byte[3 + USERNAME_LENGTH + PASSWORD_LENGTH];

    bufferSent[0] = 0x01; // VER
    bufferSent[1] = (byte) USERNAME_LENGTH; // ULEN
    System.arraycopy(bytesOfUsername, 0, bufferSent, 2, USERNAME_LENGTH);// UNAME
    bufferSent[2 + USERNAME_LENGTH] = (byte) PASSWORD_LENGTH; // PLEN
    System.arraycopy(bytesOfPassword, 0, bufferSent, 3 + USERNAME_LENGTH, // PASSWD
        PASSWORD_LENGTH);
    outputStream.write(bufferSent);
    outputStream.flush();
    // logger send bytes
    logger.debug("{}", LogMessageBuilder.build(bufferSent, MsgType.SEND));

    byte[] authenticationResult = new byte[2];
    inputStream.read(authenticationResult);
    // logger
    logger.debug("{}", LogMessageBuilder.build(authenticationResult, MsgType.RECEIVE));

    if (authenticationResult[1] != Socks5.AUTHENTICATION_SUCCEEDED) {
      // Close connection if authentication is failed.
      outputStream.close();
      inputStream.close();
      socksProxy.getProxySocket().close();
      throw new AuthenticationException("Username or password error");
    }
  }

  @Override
  public String getMethodName() {
    return "USERNAME/PASSWORD authentication";
  }

}
