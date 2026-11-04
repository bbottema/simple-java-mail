package org.simplejavamail.api.internal.outlooksupport.model;

/**
 * @see OutlookMessage
 */
public interface OutlookFileAttachment extends OutlookAttachment{
	/** The literal reader-exposed Content-ID, when one was provided. */
	default String getContentId() {
		return null;
	}
	@SuppressWarnings("ElementOnlyUsedFromTestCode")
	String getExtension();
	
	@SuppressWarnings("ElementOnlyUsedFromTestCode")
	String getFilename();
	
	String getLongFilename();
	
	@SuppressWarnings("ElementOnlyUsedFromTestCode")
	String getMimeTag();
	
	@SuppressWarnings("ElementOnlyUsedFromTestCode")
	byte[] getData();
	
	long getSize();
}
