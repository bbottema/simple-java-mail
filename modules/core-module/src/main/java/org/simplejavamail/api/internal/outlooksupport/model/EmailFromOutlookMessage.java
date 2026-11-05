package org.simplejavamail.api.internal.outlooksupport.model;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.api.outlook.OutlookMessageData;
import jakarta.activation.DataSource;
import java.util.Map;

/**
 * Wrapper class that can hold both the resulting Email (builder) and the source OutlookMessage.
 * <br>
 * Useful when data is needed which didn't convert directly into the Email (builder) instance.
 *
 * Prefer {@link OutlookEmailConversionResult} for public conversion APIs.
 */
@ApiStatus.Internal
public interface EmailFromOutlookMessage {

	EmailPopulatingBuilder getEmailBuilder();

	OutlookMessage getOutlookMessage();

	OutlookMessageData getOutlookMessageData();
	/** Transient resource identity to actual native attachment occurrence, consumed during source capture. */
	Map<DataSource, Integer> getAttachmentSourceIndices();

	OutlookEmailConversionResult toOutlookEmailConversionResult();
}
