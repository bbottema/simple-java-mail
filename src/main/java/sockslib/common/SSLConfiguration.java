

package sockslib.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

import static sockslib.utils.Util.checkNotNull;

public class SSLConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(SSLConfiguration.class);

  private KeyStoreInfo keyStoreInfo;
  private KeyStoreInfo trustKeyStoreInfo;
  private boolean needClientAuth = false;

  public SSLConfiguration(KeyStoreInfo keyStoreInfo, KeyStoreInfo trustKeyStoreInfo) {
    this(keyStoreInfo, trustKeyStoreInfo, false);
  }

  public SSLConfiguration( KeyStoreInfo keyStoreInfo,  KeyStoreInfo
      trustKeyStoreInfo, boolean clientAuth) {
    this.keyStoreInfo = keyStoreInfo;
    this.trustKeyStoreInfo = trustKeyStoreInfo;
    this.needClientAuth = clientAuth;
  }

  public SSLSocketFactory getSSLSocketFactory() throws SocksException {
    checkNotNull(trustKeyStoreInfo, "trustKeyStoreInfo may not be null");
    KeyStore keyStore = null;
    try {
      SSLContext context = SSLContext.getInstance("SSL");
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("SunX509");
      KeyStore trustKeyStore = KeyStore.getInstance(trustKeyStoreInfo.getType());
      trustKeyStore.load(new FileInputStream(trustKeyStoreInfo.getKeyStorePath()),
          trustKeyStoreInfo.getPassword().toCharArray());
      trustManagerFactory.init(trustKeyStore);

      if (keyStoreInfo != null && keyStoreInfo.getKeyStorePath() != null) {
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
        keyStore = KeyStore.getInstance(keyStoreInfo.getType());
        keyStore.load(new FileInputStream(keyStoreInfo.getKeyStorePath()), keyStoreInfo
            .getPassword().toCharArray());
        keyManagerFactory.init(keyStore, keyStoreInfo.getPassword().toCharArray());

        context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(),
            null);
      } else {
        context.init(null, trustManagerFactory.getTrustManagers(), null);
      }

      if (keyStore != null) {
        logger.info("SSL: Key store:{}", keyStoreInfo.getKeyStorePath());
      }
      logger.info("SSL: Trust key store:{}", trustKeyStoreInfo.getKeyStorePath());
      return context.getSocketFactory();
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      throw new SocksException(e.getMessage());
    }
  }

}
