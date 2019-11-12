package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;
import org.simplejavamail.internal.util.MiscUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;

class SSLConfiguration {

	private static final Logger LOGGER = LoggerFactory.getLogger(SSLConfiguration.class);

	private final KeyStoreInfo keyStoreInfo;
	private final KeyStoreInfo trustKeyStoreInfo;

	@SuppressWarnings("SameParameterValue")
	private SSLConfiguration(final KeyStoreInfo keyStoreInfo, final KeyStoreInfo trustKeyStoreInfo) {
		this.keyStoreInfo = keyStoreInfo;
		this.trustKeyStoreInfo = trustKeyStoreInfo;
	}

	public SSLSocketFactory getSSLSocketFactory()
			throws SocksException {
		MiscUtil.checkNotNull(trustKeyStoreInfo, "trustKeyStoreInfo may not be null");
		FileInputStream s1 = null;
		FileInputStream s2 = null;
		try {
			final SSLContext context = SSLContext.getInstance("SSL");
			final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("SunX509");
			final KeyStore trustKeyStore = KeyStore.getInstance(trustKeyStoreInfo.getType());
			trustKeyStore.load(s1 = new FileInputStream(trustKeyStoreInfo.getKeyStorePath()), trustKeyStoreInfo.getPassword().toCharArray());
			trustManagerFactory.init(trustKeyStore);
			KeyStore keyStore = null;

			if (keyStoreInfo != null && keyStoreInfo.getKeyStorePath() != null) {
				final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
				keyStore = KeyStore.getInstance(keyStoreInfo.getType());
				keyStore.load(s2 = new FileInputStream(keyStoreInfo.getKeyStorePath()), keyStoreInfo.getPassword().toCharArray());
				keyManagerFactory.init(keyStore, keyStoreInfo.getPassword().toCharArray());

				context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
			} else {
				context.init(null, trustManagerFactory.getTrustManagers(), null);
			}

			if (keyStore != null) {
				LOGGER.info("SSL: Key store:{}", keyStoreInfo.getKeyStorePath());
			}
			LOGGER.info("SSL: Trust key store:{}", trustKeyStoreInfo.getKeyStorePath());
			return context.getSocketFactory();
		} catch (final Exception e) {
			LOGGER.error(e.getMessage(), e);
			throw new SocksException(e.getMessage());
		} finally {
			tryCloseStream(s1);
			tryCloseStream(s2);
		}
	}

	private static void tryCloseStream(final FileInputStream s1) {
		if (s1 != null) {
			try {
				s1.close();
			} catch (final IOException e) {
				LOGGER.error("unable to close stream", e);
			}
		}
	}

}
