package org.simplejavamail.internal.modules;

import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.InputStream;

public interface OutlookModule {
	EmailPopulatingBuilder outlookMsgToEmailBuilder(@Nonnull final File msgFile, @Nonnull EmailStartingBuilder emailStartingBuilder);
	EmailPopulatingBuilder outlookMsgToEmailBuilder(@Nonnull final String msgData, @Nonnull EmailStartingBuilder emailStartingBuilder);
	EmailPopulatingBuilder outlookMsgToEmailBuilder(@Nonnull final InputStream msgInputStream, @Nonnull EmailStartingBuilder emailStartingBuilder);
}