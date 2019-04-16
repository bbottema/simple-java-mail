package org.simplejavamail.api.internal.outlooksupport.model;

public interface OutlookFileAttachment extends OutlookAttachment{
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