package org.simplejavamail.internal.modules;

import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.general.EmailPopulatingBuilderFactory;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;

import org.jetbrains.annotations.NotNull;
import java.io.File;
import java.io.InputStream;

public interface OutlookModule {
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@NotNull File msgFile, @NotNull EmailStartingBuilder emailStartingBuilder, @NotNull final EmailPopulatingBuilderFactory builderFactory);
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@NotNull String msgData, @NotNull EmailStartingBuilder emailStartingBuilder, @NotNull final EmailPopulatingBuilderFactory builderFactory);
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@NotNull InputStream msgInputStream, @NotNull EmailStartingBuilder emailStartingBuilder, @NotNull final EmailPopulatingBuilderFactory builderFactory);
}