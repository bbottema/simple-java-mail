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
package org.simplejavamail.api.internal.outlooksupport.model;

import org.jetbrains.annotations.Nullable;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies the interface of {@code org.simplejavamail.outlookmessageparser.model.OutlookMessage}.
 * <br>
 * This is so that Simple Java Mail will compile using this interface, which has an implementation in
 * the Outlook Module, which proxies to the actual Outlook Message Parser's OutlookMessage as delegate.
 * <br>
 * This way, Simple Java Mail can code against this native OutlookMessage and still compile, even if
 * the module is not loaded.
 */
public interface OutlookMessage {
	Map<String, OutlookFileAttachment> fetchCIDMap();
	
	List<OutlookFileAttachment> fetchTrueAttachments();
	
	List<OutlookAttachment> getOutlookAttachments();
	
	List<OutlookRecipient> getRecipients();
	
	String getFromEmail();
	
	String getFromName();
	
	String getDisplayTo();
	
	String getDisplayCc();
	
	String getDisplayBcc();
	
	String getMessageClass();
	
	String getMessageId();
	
	String getSubject();
	
	String getToEmail();
	
	String getToName();
	
	OutlookRecipient getToRecipient();
	
	List<OutlookRecipient> getCcRecipients();
	
	List<OutlookRecipient> getBccRecipients();
	
	@SuppressWarnings("ElementOnlyUsedFromTestCode")
	String getBodyText();
	
	@SuppressWarnings("ElementOnlyUsedFromTestCode")
	String getBodyRTF();
	
	@SuppressWarnings("ElementOnlyUsedFromTestCode")
	String getBodyHTML();
	
	String getConvertedBodyHTML();
	
	String getHeaders();
	
	Date getDate();
	
	Date getClientSubmitTime();
	
	Date getCreationDate();
	
	Date getLastModificationDate();
	
	Set<String> getPropertiesAsHex();
	
	Set<Integer> getPropertyCodes();
	
	String getPropertyListing();
	
	String getReplyToEmail();
	
	String getReplyToName();

	@Nullable
	OutlookSmime getSmimeMime();
}