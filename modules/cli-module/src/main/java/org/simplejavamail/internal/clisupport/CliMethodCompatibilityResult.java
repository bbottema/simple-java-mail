package org.simplejavamail.internal.clisupport;

import lombok.AllArgsConstructor;
import lombok.Value;
import org.jetbrains.annotations.Nullable;

@Value
@AllArgsConstructor
public class CliMethodCompatibilityResult {
	boolean compatible;
	@Nullable String reason;

	public CliMethodCompatibilityResult(boolean compatible) {
		this(compatible, null);
	}
}