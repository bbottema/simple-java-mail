/*
 * Copyright 2015-2025 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package sockslib.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * The class <code>SSLConfiguration</code> represents a configuration of SSL.
 *
 * @author Youchao Feng
 * @version 1.0
 */
public class SSLConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(SSLConfiguration.class);

	private final String trustKeyStore;

	public SSLConfiguration(@SuppressWarnings("SameParameterValue") String trustKeyStore) {
		this.trustKeyStore = trustKeyStore;
	}

	public SSLSocketFactory getSSLSocketFactory()
			throws SSLConfigurationException {
		try {
			SSLContext context = SSLContext.getInstance("SSL");
			TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("SunX509");
			context.init(null, trustManagerFactory.getTrustManagers(), null);

			logger.info("SSL: Trust key store:{}", trustKeyStore);
			return context.getSocketFactory();
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new SSLConfigurationException(e.getMessage());
		}
	}
}
