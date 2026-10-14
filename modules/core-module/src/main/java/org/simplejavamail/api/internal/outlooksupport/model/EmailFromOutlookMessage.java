package org.simplejavamail.api.internal.outlooksupport.model;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.api.outlook.OutlookMessageData;

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

	OutlookEmailConversionResult toOutlookEmailConversionResult();
}
