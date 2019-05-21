package org.simplejavamail.api.internal.outlooksupport.model;

import javax.annotation.Nullable;
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