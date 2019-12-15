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
package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.Serializable;

/**
 * Indicates S/MIME details about an email. Used to show how a converted message was signed / encrypted and by whom.
 * <p>
 * Note: the difference between this and {@link org.simplejavamail.api.internal.smimesupport.model.SmimeDetails} is that
 * this class is intended for exposing S/MIME metadata to the end user, while the other class is for internal use
 * by the S/MIME module alone.
 *
 * @see EmailPopulatingBuilder#getOriginalSmimeDetails()
 */
public interface OriginalSmimeDetails extends Serializable {

	enum SmimeMode {
		PLAIN, SIGNED, ENCRYPTED, SIGNED_ENCRYPTED
	}

	@NotNull SmimeMode getSmimeMode();
	@Nullable String getSmimeMime();
	@Nullable String getSmimeType();
	@Nullable String getSmimeName();
	@Nullable String getSmimeProtocol();
	@Nullable String getSmimeMicalg();
	@Nullable String getSmimeSignedBy();
	@Nullable Boolean getSmimeSignatureValid();
}