package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/** A mail server identified by a received report. Its name and naming format stay together; no DNS lookup or connection is performed. */
public interface ReportedMailServer extends Serializable {

	/** The reported naming format, commonly {@code dns}. Unfamiliar formats are preserved rather than treated as hostnames. */
	@NotNull String getType();

	/** The reported identifier without its type prefix. It is not necessarily an Internet hostname. */
	@NotNull String getName();

	/** Identifies the snapshot without printing the server's reported type or name. */
	@Override
	@NotNull String toString();
}
