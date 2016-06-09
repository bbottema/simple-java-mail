

package sockslib.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sockslib.common.*;
import sockslib.common.methods.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.ArrayList;
import java.util.List;


public class Socks5 implements SocksProxy {


  public static final byte SOCKS_VERSION = 0x05;

  public static final byte AUTHENTICATION_SUCCEEDED = 0x00;

  protected static final Logger logger = LoggerFactory.getLogger(Socks5.class);
  private SocksProxy chainProxy;

  private ProxyCredentials credentials = new ProxyCredentials();

  private InetAddress inetAddress;

  private int port = SOCKS_DEFAULT_PORT;

  private Socket proxySocket;

  private List<SocksMethod> acceptableMethods;

  private SocksMethodRequester socksMethodRequester = new GenericSocksMethodRequester();

  private SocksCommandSender socksCmdSender = new GenericSocksCommandSender();

  private boolean alwaysResolveAddressLocally = false;


  public Socks5(SocketAddress socketAddress, String username, String password) {
    this(socketAddress);
    setCredentials(new ProxyCredentials(username, password));
  }


  public Socks5(String host, int port) throws UnknownHostException {
    this(InetAddress.getByName(host), port);
  }


  public Socks5(InetAddress inetAddress, int port) {
    this(new InetSocketAddress(inetAddress, port));
  }


  public Socks5(SocketAddress socketAddress) {
    this(null, socketAddress);
  }

  public Socks5(SocksProxy chainProxy, SocketAddress socketAddress) {
    init();
    if (socketAddress instanceof InetSocketAddress) {
      inetAddress = ((InetSocketAddress) socketAddress).getAddress();
      port = ((InetSocketAddress) socketAddress).getPort();
      this.setChainProxy(chainProxy);
    } else {
      throw new IllegalArgumentException("Only supports java.net.InetSocketAddress");
    }
  }


  public Socks5(String host, int port, ProxyCredentials credentials) throws UnknownHostException {
    init();
    this.inetAddress = InetAddress.getByName(host);
    this.port = port;
    this.credentials = credentials;
  }


  private void init() {
    acceptableMethods = new ArrayList<>();
    acceptableMethods.add(new NoAuthenticationRequiredMethod());
    acceptableMethods.add(new UsernamePasswordMethod());
  }

  @Override
  public void buildConnection() throws  IOException {
    if (inetAddress == null) {
      throw new IllegalArgumentException("Please set inetAddress before calling buildConnection.");
    }
    if (proxySocket == null) {
      proxySocket = createProxySocket(inetAddress, port);
    } else if (!proxySocket.isConnected()) {
      proxySocket.connect(new InetSocketAddress(inetAddress, port));
    }

    SocksMethod method =
        socksMethodRequester.doRequest(acceptableMethods, proxySocket, SOCKS_VERSION);
    method.doMethod(this);
  }

  @Override
  public CommandReplyMessage requestConnect(String host, int port) throws
      IOException {
    if (!alwaysResolveAddressLocally) {
      // resolve address in SOCKS server
      return socksCmdSender.send(proxySocket, SocksCommand.CONNECT, host, port, SOCKS_VERSION);

    } else {
      // resolve address in local.
      InetAddress address = InetAddress.getByName(host);
      return socksCmdSender.send(proxySocket, SocksCommand.CONNECT, address, port, SOCKS_VERSION);
    }
  }

  @Override
  public CommandReplyMessage requestConnect(InetAddress address, int port) throws
      IOException {
    return socksCmdSender.send(proxySocket, SocksCommand.CONNECT, address, port, SOCKS_VERSION);
  }

  @Override
  public CommandReplyMessage requestConnect(SocketAddress address) throws
      IOException {
    return socksCmdSender.send(proxySocket, SocksCommand.CONNECT, address, SOCKS_VERSION);
  }

  @Override
  public CommandReplyMessage requestBind(String host, int port) throws  IOException {
    return socksCmdSender.send(proxySocket, SocksCommand.BIND, host, port, SOCKS_VERSION);
  }

  @Override
  public CommandReplyMessage requestBind(InetAddress inetAddress, int port) throws
      SocksException, IOException {
    return socksCmdSender.send(proxySocket, SocksCommand.BIND, inetAddress, port, SOCKS_VERSION);
  }

  @Override
  public Socket accept() throws  IOException {
    CommandReplyMessage messge = socksCmdSender.checkServerReply(proxySocket.getInputStream());
    logger.debug("accept a connection from:{}", messge.getSocketAddress());
    return this.proxySocket;
  }

  @Override
  public CommandReplyMessage requestUdpAssociate(String host, int port) throws SocksException,
      IOException {
    return socksCmdSender.send(proxySocket, SocksCommand.UDP_ASSOCIATE, new InetSocketAddress
        (host, port), SOCKS_VERSION);
  }

  @Override
  public CommandReplyMessage requestUdpAssociate(InetAddress address, int port) throws
      SocksException, IOException {
    return socksCmdSender.send(proxySocket, SocksCommand.UDP_ASSOCIATE, new InetSocketAddress
        (address, port), SOCKS_VERSION);
  }

  @Override
  public int getPort() {
    return port;
  }

  @Override
  public Socks5 setPort(int port) {
    this.port = port;
    return this;
  }

  @Override
  public Socket getProxySocket() {
    return proxySocket;
  }

  @Override
  public Socks5 setProxySocket(Socket proxySocket) {
    this.proxySocket = proxySocket;
    return this;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return proxySocket.getInputStream();
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    return proxySocket.getOutputStream();
  }

  @Override
  public List<SocksMethod> getAcceptableMethods() {
    return acceptableMethods;
  }

  @Override
  public Socks5 setAcceptableMethods(List<SocksMethod> acceptableMethods) {
    this.acceptableMethods = acceptableMethods;
    SocksMethodRegistry.overWriteRegistry(acceptableMethods);
    return this;
  }

  @Override
  public ProxyCredentials getCredentials() {
    return credentials;
  }

  @Override
  public Socks5 setCredentials(ProxyCredentials credentials) {
    this.credentials = credentials;
    return this;
  }

  @Override
  public SocksMethodRequester getSocksMethodRequester() {
    return socksMethodRequester;
  }

  @Override
  public Socks5 setSocksMethodRequester(SocksMethodRequester requester) {
    this.socksMethodRequester = requester;
    return this;
  }

  @Override
  public SocksProxy copy() {
    Socks5 socks5 = new Socks5(inetAddress, port);
    socks5.setAcceptableMethods(acceptableMethods).setAlwaysResolveAddressLocally
        (alwaysResolveAddressLocally).setCredentials(credentials).setSocksMethodRequester
        (socksMethodRequester).setChainProxy(chainProxy);
    return socks5;
  }

  @Override
  public SocksProxy copyWithoutChainProxy() {
    return copy().setChainProxy(null);
  }

  @Override
  public int getSocksVersion() {
    return SOCKS_VERSION;
  }

  @Override
  public SocksProxy getChainProxy() {
    return chainProxy;
  }

  @Override
  public SocksProxy setChainProxy(SocksProxy chainProxy) {
    this.chainProxy = chainProxy;
    return this;
  }

  @Override
  public Socks5 setHost(String host) throws UnknownHostException {
    inetAddress = InetAddress.getByName(host);
    return this;
  }

  @Override
  public InetAddress getInetAddress() {
    return inetAddress;
  }


  public Socks5 setInetAddress(InetAddress inetAddress) {
    this.inetAddress = inetAddress;
    return this;
  }

  @Override
  public String toString() {
    StringBuilder stringBuffer = new StringBuilder("[SOCKS5:");
    stringBuffer.append(new InetSocketAddress(inetAddress, port)).append("]");
    if (getChainProxy() != null) {
      return stringBuffer.append(" --> ").append(getChainProxy().toString()).toString();
    }
    return stringBuffer.toString();
  }

  @Override
  public Socket createProxySocket(InetAddress address, int port) throws IOException {
    return new Socket(address, port);
  }

  @Override
  public Socket createProxySocket() throws IOException {
    return new Socket();
  }

  public boolean isAlwaysResolveAddressLocally() {
    return alwaysResolveAddressLocally;
  }

  public Socks5 setAlwaysResolveAddressLocally(boolean alwaysResolveAddressLocally) {
    this.alwaysResolveAddressLocally = alwaysResolveAddressLocally;
    return this;
  }

}
