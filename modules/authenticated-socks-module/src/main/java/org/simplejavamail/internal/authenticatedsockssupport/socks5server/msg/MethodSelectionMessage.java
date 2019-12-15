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
package org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

public final class MethodSelectionMessage {

	private static final Logger LOGGER = LoggerFactory.getLogger(MethodSelectionMessage.class);

	public static int readVersion(final InputStream inputStream)
			throws IOException {
		LOGGER.trace("MethodSelectionMessage.read");
		final int version = StreamUtil.checkEnd(inputStream.read());
		final int methodNum = StreamUtil.checkEnd(inputStream.read());
		for (int i = 0; i < methodNum; i++) {
			StreamUtil.checkEnd(inputStream.read()); // read method byte
		}
		return version;
	}
}
