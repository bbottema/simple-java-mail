package org.simplejavamail.internal.modules;

import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.InputStream;

public interface OutlookModule {
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@Nonnull final File msgFile, @Nonnull EmailStartingBuilder emailStartingBuilder);
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@Nonnull final String msgData, @Nonnull EmailStartingBuilder emailStartingBuilder);
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@Nonnull final InputStream msgInputStream, @Nonnull EmailStartingBuilder emailStartingBuilder);
}