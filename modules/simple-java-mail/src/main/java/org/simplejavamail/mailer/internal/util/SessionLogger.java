/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.mailer.internal.util;

import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.slf4j.Logger;

import javax.mail.Session;
import java.util.Properties;

import static org.slf4j.LoggerFactory.getLogger;

public class SessionLogger {

	private static final Logger LOGGER = getLogger(SessionLogger.class);

	/**
	 * Simply logs host details, credentials used and whether authentication will take place and finally the transport protocol used.
	 */
	public static void logSession(final Session session, boolean async, final String activity) {
		final TransportStrategy transportStrategy = TransportStrategy.findStrategyForSession(session);
		final Properties properties = session.getProperties();
		final String sessionDetails = (transportStrategy != null) ? transportStrategy.toString(properties) : properties.toString();
		LOGGER.debug("starting{} {} with {}", async ? " async" : "", activity, sessionDetails);
	}
}
