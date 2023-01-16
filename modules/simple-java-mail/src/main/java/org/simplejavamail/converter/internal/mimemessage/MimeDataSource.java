package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.DataSource;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@Getter
@Builder
public class MimeDataSource implements Comparable<MimeDataSource> {
	private final String name;
	private final DataSource dataSource;
	@Nullable private final String contentDescription;
	@Nullable private final String contentTransferEncoding;

	@Override
	public int compareTo(@NotNull final MimeDataSource o) {
		int keyComparison = getName().compareTo(o.getName());
		if (keyComparison != 0) {
			return keyComparison;
		}
		return Integer.compare(getDataSource().hashCode(), o.getDataSource().hashCode());
	}

	@Override
	public boolean equals(final Object o) {
		return this == o ||
				(o instanceof MimeDataSource && compareTo((MimeDataSource) o) == 0);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, dataSource);
	}
}