package org.simplejavamail.internal.modules;

import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;

import org.jetbrains.annotations.NotNull;
import java.io.File;
import java.io.InputStream;

public interface OutlookModule {
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@NotNull final File msgFile, @NotNull EmailStartingBuilder emailStartingBuilder);
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@NotNull final String msgData, @NotNull EmailStartingBuilder emailStartingBuilder);
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@NotNull final InputStream msgInputStream, @NotNull EmailStartingBuilder emailStartingBuilder);
}