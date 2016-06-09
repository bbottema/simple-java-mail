

package sockslib.client;

import sockslib.common.Credentials;
import sockslib.common.SocksException;
import sockslib.common.methods.SocksMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;


public interface SocksProxy {


  int SOCKS_DEFAULT_PORT = 1080;


  Socket getProxySocket();


  SocksProxy setProxySocket(Socket socket);


  int getPort();


  SocksProxy setPort(int port);


  InetAddress getInetAddress();


  SocksProxy setHost(String host) throws UnknownHostException;


  void buildConnection() throws IOException, SocksException;


  CommandReplyMessage requestConnect(String host, int port) throws SocksException, IOException;


  CommandReplyMessage requestConnect(InetAddress address, int port) throws SocksException,
      IOException;


  CommandReplyMessage requestConnect(SocketAddress address) throws SocksException, IOException;


  CommandReplyMessage requestBind(String host, int port) throws SocksException, IOException;


  CommandReplyMessage requestBind(InetAddress inetAddress, int port) throws
      SocksException, IOException;


  Socket accept() throws SocksException, IOException;


  CommandReplyMessage requestUdpAssociate(String host, int port) throws SocksException, IOException;


  CommandReplyMessage requestUdpAssociate(InetAddress address, int port) throws SocksException,
      IOException;


  InputStream getInputStream() throws IOException;


  OutputStream getOutputStream() throws IOException;


  Credentials getCredentials();


  SocksProxy setCredentials(Credentials credentials);


  List<SocksMethod> getAcceptableMethods();


  SocksProxy setAcceptableMethods(List<SocksMethod> methods);


  SocksMethodRequester getSocksMethodRequester();


  SocksProxy setSocksMethodRequester(SocksMethodRequester requester);


  int getSocksVersion();


  SocksProxy copy();


  SocksProxy copyWithoutChainProxy();


  SocksProxy getChainProxy();


  SocksProxy setChainProxy(SocksProxy chainProxy);


  Socket createProxySocket(InetAddress address, int port) throws IOException;


  Socket createProxySocket() throws IOException;

}
