package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.internal.email.EmailInspection;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Upgrades captured source occurrences for a conversion rewrite; it never reconciles later business edits with history. */
final class OriginalSourceRetention {

	private final List<EmailSourcePart> originals;
	private final Map<EmailSourcePart, EmailSourcePart> replaced = new IdentityHashMap<>();

	private OriginalSourceRetention(final List<EmailSourcePart> originals) {
		this.originals = originals;
	}

	static void retain(final InternalEmailPopulatingBuilder builder, final List<EmailSourcePart> originals) {
		if (originals.isEmpty()) return;
		final OriginalSourceRetention retention = new OriginalSourceRetention(originals);
		final EmailSourceData source = retention.source(builder.getSourceData());
		final List<EmailProblem> problems = new ArrayList<>();
		for (EmailProblem problem : builder.getProblems()) {
			final List<EmailSourceElement> affected = new ArrayList<>();
			for (EmailSourceElement element : problem.getSources()) {
				affected.add(element instanceof EmailSourcePart ? retention.part((EmailSourcePart) element) : element);
			}
			problems.add(EmailInspection.problem(problem.getOrigin(), problem.getOperation(), problem.getCode(), problem.getLocation(),
					problem.getSourceValue(), problem.getMessage(), affected));
		}
		builder.retainInspection(source, problems);
	}

	private EmailSourceData source(final EmailSourceData source) {
		final List<EmailSourcePart> parts = new ArrayList<>();
		for (EmailSourcePart part : source.getParts()) parts.add(part(part));
		final List<EmailSourceData> derived = new ArrayList<>();
		for (EmailSourceData view : source.getDerivedSources()) derived.add(source(view));
		return EmailInspection.source(source.getOrigin(), source.getFormat(), source.getRepresentation(), source.getHeaders(),
				source.getProperties(), parts, derived);
	}

	private EmailSourcePart part(final EmailSourcePart part) {
		final EmailSourcePart known = replaced.get(part);
		if (known != null) return known;
		for (EmailSourcePart original : originals) {
			if (Objects.equals(original.getOrigin().getReference(), part.getOrigin().getReference())
					&& original.getLocation().equals(part.getLocation())) {
				replaced.put(part, original);
				return original;
			}
		}
		final List<EmailSourcePart> children = new ArrayList<>();
		boolean changed = false;
		for (EmailSourcePart child : part.getChildren()) {
			final EmailSourcePart next = part(child);
			children.add(next);
			changed |= next != child;
		}
		final EmailSourcePart result = changed ? EmailInspection.part(part.getOrigin(), part.getLocation(), part.getHeaders(),
				part.getProperties(), children, part.getText(), part.getContent()) : part;
		replaced.put(part, result);
		return result;
	}
}
