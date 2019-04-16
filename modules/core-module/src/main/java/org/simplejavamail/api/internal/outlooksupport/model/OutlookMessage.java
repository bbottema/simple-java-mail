package org.simplejavamail.api.internal.outlooksupport.model;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface OutlookMessage {
	Map<String, OutlookFileAttachment> fetchCIDMap();
	
	List<OutlookFileAttachment> fetchTrueAttachments();
	
	String toLongString();
	
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
	
	String getSmimeMime();
	
	String getSmimeType();
	
	String getSmimeName();
}
