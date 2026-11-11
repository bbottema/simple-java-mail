package org.simplejavamail.internal.email;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.EmailSourceHeader;
import org.simplejavamail.api.email.EmailSourceIdentity;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.EmailSourceProperty;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Internal construction and detachment of captured inspection values. Owned immutable values are shared, including byte storage;
 * no value retains a live reader, resource callback, parser, enclosing Email or Throwable.
 */
@ApiStatus.Internal
public final class EmailInspection {

	private static final EmailSourceData EMPTY = new Source(null, null, null, Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList(), Collections.emptyList());

	private EmailInspection() { }

	@NotNull
	public static EmailSourceData empty() {
		return EMPTY;
	}

	@NotNull
	public static EmailSourceIdentity origin(@Nullable final String messageId) {
		return new Origin(UUID.randomUUID(), messageId);
	}

	@NotNull
	public static EmailSourceHeader header(@NotNull final EmailSourceIdentity origin, @NotNull final String location,
			@NotNull final String name, @Nullable final String value) {
		return new Header(new Snapshot().origin(origin), location, name, value);
	}

	@NotNull
	public static EmailSourceProperty property(@NotNull final EmailSourceIdentity origin, @NotNull final String location,
			@NotNull final String name, @Nullable final String type, @Nullable final String value,
			@Nullable final EmailSourceContent content) {
		final Snapshot snapshot = new Snapshot();
		return new Property(snapshot.origin(origin), location, name, type, value, snapshot.content(content));
	}

	@NotNull
	public static EmailSourceContent content(@NotNull final byte[] bytes, @NotNull final EmailSourceContent.Representation representation,
			final boolean fullyCaptured) {
		return new Content(bytes, representation, fullyCaptured);
	}

    /** Shares storage already exclusively controlled by library-owned read-only content; the array must never be changed afterward. */
    public static EmailSourceContent ownedContent(final byte[] bytes, final EmailSourceContent.Representation representation, final boolean fullyCaptured) {
        return new Content(bytes, representation, fullyCaptured, false);
    }

	@NotNull
	public static EmailSourcePart part(@NotNull final EmailSourceIdentity origin, @NotNull final String location,
			@NotNull final List<? extends EmailSourceHeader> headers, @NotNull final List<? extends EmailSourceProperty> properties,
			@NotNull final List<? extends EmailSourcePart> children, @Nullable final String text, @Nullable final EmailSourceContent content) {
		return new Snapshot().part(origin, location, headers, properties, children, text, content);
	}

	@NotNull
	public static EmailSourceData source(@Nullable final EmailSourceIdentity origin, @Nullable final EmailSourceData.Format format,
			@Nullable final EmailSourceData.Representation representation, @NotNull final List<? extends EmailSourceHeader> headers,
			@NotNull final List<? extends EmailSourceProperty> properties, @NotNull final List<? extends EmailSourcePart> parts,
			@NotNull final List<? extends EmailSourceData> derivedSources) {
		return new Snapshot().source(origin, format, representation, headers, properties, parts, derivedSources);
	}

	@NotNull
	public static EmailSourceData copyOf(@Nullable final EmailSourceData source) {
		return source == null ? EMPTY : new Snapshot().source(source);
	}

	@NotNull
	public static EmailSourceData withDerivedSources(@NotNull final EmailSourceData source,
			@NotNull final List<? extends EmailSourceData> derivedSources) {
		return source(source.getOrigin(), source.getFormat(), source.getRepresentation(), source.getHeaders(), source.getProperties(),
				source.getParts(), derivedSources);
	}

	@NotNull
	public static EmailSourceData withParts(@NotNull final EmailSourceData source, @NotNull final List<? extends EmailSourcePart> parts) {
		return source(source.getOrigin(), source.getFormat(), source.getRepresentation(), source.getHeaders(), source.getProperties(),
				parts, source.getDerivedSources());
	}

	@NotNull
	public static EmailSourceData withProperties(@NotNull final EmailSourceData source,
			@NotNull final List<? extends EmailSourceProperty> properties) {
		return source(source.getOrigin(), source.getFormat(), source.getRepresentation(), source.getHeaders(), properties,
				source.getParts(), source.getDerivedSources());
	}

	@NotNull
	public static EmailProblem problem(@NotNull final EmailSourceIdentity origin, @NotNull final EmailProblem.Operation operation,
			@NotNull final EmailProblem.Code code, @NotNull final String location, @Nullable final String sourceValue,
			@NotNull final String message) {
		return problem(origin, operation, code, location, sourceValue, message, Collections.emptyList());
	}

	@NotNull
	public static EmailProblem problem(@NotNull final EmailSourceIdentity origin, @NotNull final EmailProblem.Operation operation,
			@NotNull final EmailProblem.Code code, @NotNull final String location, @Nullable final String sourceValue,
			@NotNull final String message, @NotNull final List<? extends EmailSourceElement> sources) {
		return new Snapshot().problem(origin, operation, code, location, sourceValue, message, sources);
	}

	/** Qualify the containing view's diagnostic label while keeping direct references to the original source occurrences. */
	@NotNull
	public static EmailProblem qualify(@NotNull final EmailProblem problem, @NotNull final String prefix) {
		return problem(problem.getOrigin(), problem.getOperation(), problem.getCode(), Objects.requireNonNull(prefix, "prefix") + problem.getLocation(),
				problem.getSourceValue(), problem.getMessage(), problem.getSources());
	}

	@NotNull
	public static List<EmailProblem> problems(@Nullable final List<? extends EmailProblem> problems) {
		if (problems == null) return Collections.emptyList();
		final Snapshot snapshot = new Snapshot();
		return snapshot.list(problems, snapshot::problem);
	}

	/** Detach source references while sharing already owned immutable occurrences. */
	@NotNull
	public static List<EmailSourceElement> elements(@NotNull final List<? extends EmailSourceElement> sources) {
		final Snapshot snapshot = new Snapshot();
		return snapshot.list(sources, snapshot::element);
	}

	/** Detach foreign read-contract implementations once; library-owned captured values need no duplicate storage. */
	private static final class Snapshot {
		private final Map<Object, Object> copies = new IdentityHashMap<>();

		private EmailSourceIdentity origin(final EmailSourceIdentity value) {
			Objects.requireNonNull(value, "origin");
			if (value instanceof Origin) return value;
			final EmailSourceIdentity known = (EmailSourceIdentity) copies.get(value);
			if (known != null) return known;
			final EmailSourceIdentity result = new Origin(value.getReference(), value.getMessageId());
			copies.put(value, result);
			return result;
		}

		private EmailSourceHeader header(final EmailSourceHeader value) {
			Objects.requireNonNull(value, "header");
			if (value instanceof Header) return value;
			final EmailSourceHeader known = (EmailSourceHeader) copies.get(value);
			if (known != null) return known;
			final EmailSourceHeader result = new Header(origin(value.getOrigin()), value.getLocation(), value.getName(), value.getRawValue());
			copies.put(value, result);
			return result;
		}

		private EmailSourceContent content(final EmailSourceContent value) {
			if (value == null || value instanceof Content) return value;
			final EmailSourceContent known = (EmailSourceContent) copies.get(value);
			if (known != null) return known;
			final EmailSourceContent result = new Content(value.readAllBytes(), value.getRepresentation(), value.isFullyCaptured());
			copies.put(value, result);
			return result;
		}

		private EmailSourceProperty property(final EmailSourceProperty value) {
			Objects.requireNonNull(value, "property");
			if (value instanceof Property) return value;
			final EmailSourceProperty known = (EmailSourceProperty) copies.get(value);
			if (known != null) return known;
			final EmailSourceProperty result = new Property(origin(value.getOrigin()), value.getLocation(), value.getName(), value.getType(),
					value.getValue(), content(value.getContent()));
			copies.put(value, result);
			return result;
		}

		private EmailSourcePart part(final EmailSourcePart value) {
			Objects.requireNonNull(value, "part");
			if (value instanceof Part) return value;
			final EmailSourcePart known = (EmailSourcePart) copies.get(value);
			if (known != null) return known;
			final EmailSourcePart result = part(value.getOrigin(), value.getLocation(), value.getHeaders(), value.getProperties(),
					value.getChildren(), value.getText(), value.getContent());
			copies.put(value, result);
			return result;
		}

		private EmailSourcePart part(final EmailSourceIdentity origin, final String location,
				final List<? extends EmailSourceHeader> headers, final List<? extends EmailSourceProperty> properties,
				final List<? extends EmailSourcePart> children, final String text, final EmailSourceContent content) {
			return new Part(origin(origin), location, list(headers, this::header), list(properties, this::property),
					list(children, this::part), text, content(content));
		}

		private EmailSourceData source(final EmailSourceData value) {
			Objects.requireNonNull(value, "source");
			if (value instanceof Source) return value;
			final EmailSourceData known = (EmailSourceData) copies.get(value);
			if (known != null) return known;
			final EmailSourceData result = source(value.getOrigin(), value.getFormat(), value.getRepresentation(), value.getHeaders(),
					value.getProperties(), value.getParts(), value.getDerivedSources());
			copies.put(value, result);
			return result;
		}

		private EmailSourceData source(final EmailSourceIdentity origin, final EmailSourceData.Format format,
				final EmailSourceData.Representation representation, final List<? extends EmailSourceHeader> headers,
				final List<? extends EmailSourceProperty> properties, final List<? extends EmailSourcePart> parts,
				final List<? extends EmailSourceData> derivedSources) {
			return new Source(origin == null ? null : origin(origin), format, representation, list(headers, this::header),
					list(properties, this::property), list(parts, this::part), list(derivedSources, this::source));
		}

		private EmailSourceElement element(final EmailSourceElement value) {
			Objects.requireNonNull(value, "source element");
			if (value instanceof Element) return value;
			if (value instanceof EmailSourceHeader) return header((EmailSourceHeader) value);
			if (value instanceof EmailSourceProperty) return property((EmailSourceProperty) value);
			if (value instanceof EmailSourcePart) return part((EmailSourcePart) value);
			final EmailSourceElement known = (EmailSourceElement) copies.get(value);
			if (known != null) return known;
			final EmailSourceElement result = new Element(origin(value.getOrigin()), value.getLocation());
			copies.put(value, result);
			return result;
		}

		private EmailProblem problem(final EmailProblem value) {
			Objects.requireNonNull(value, "problem");
			if (value instanceof Problem) return value;
			final EmailProblem known = (EmailProblem) copies.get(value);
			if (known != null) return known;
			final EmailProblem result = problem(value.getOrigin(), value.getOperation(), value.getCode(), value.getLocation(),
					value.getSourceValue(), value.getMessage(), value.getSources());
			copies.put(value, result);
			return result;
		}

		private EmailProblem problem(final EmailSourceIdentity origin, final EmailProblem.Operation operation, final EmailProblem.Code code,
				final String location, final String sourceValue, final String message, final List<? extends EmailSourceElement> sources) {
			return new Problem(origin(origin), operation, code, location, sourceValue, message, list(sources, this::element));
		}

		private <T, R> List<R> list(final List<? extends T> values, final Function<T, R> snapshot) {
			Objects.requireNonNull(values, "values");
			if (values.isEmpty()) return Collections.emptyList();
			final List<R> result = new ArrayList<>(values.size());
			for (final T value : values) result.add(snapshot.apply(value));
			return Collections.unmodifiableList(result);
		}
	}

	private static class Element implements EmailSourceElement {
		private static final long serialVersionUID = 1L;
		private final EmailSourceIdentity origin;
		private final String location;

		private Element(final EmailSourceIdentity origin, final String location) {
			this.origin = Objects.requireNonNull(origin, "origin");
			this.location = Objects.requireNonNull(location, "location");
		}

		@Override public EmailSourceIdentity getOrigin() { return origin; }
		@Override public String getLocation() { return location; }
	}

	private static final class Header extends Element implements EmailSourceHeader {
		private static final long serialVersionUID = 1L;
		private final String name;
		private final String value;

		private Header(final EmailSourceIdentity origin, final String location, final String name, final String value) {
			super(origin, location);
			this.name = Objects.requireNonNull(name, "name");
			this.value = value;
		}

		@Override public String getName() { return name; }
		@Override public String getRawValue() { return value; }
	}

	private static final class Property extends Element implements EmailSourceProperty {
		private static final long serialVersionUID = 1L;
		private final String name;
		private final String type;
		private final String value;
		private final EmailSourceContent content;

		private Property(final EmailSourceIdentity origin, final String location, final String name, final String type,
				final String value, final EmailSourceContent content) {
			super(origin, location);
			this.name = Objects.requireNonNull(name, "name");
			this.type = type;
			this.value = value;
			this.content = content;
		}

		@Override public String getName() { return name; }
		@Override public String getType() { return type; }
		@Override public String getValue() { return value; }
		@Override public EmailSourceContent getContent() { return content; }
	}

	private static final class Part extends Element implements EmailSourcePart {
		private static final long serialVersionUID = 1L;
		private final List<EmailSourceHeader> headers;
		private final List<EmailSourceProperty> properties;
		private final List<EmailSourcePart> children;
		private final String text;
		private final EmailSourceContent content;

		private Part(final EmailSourceIdentity origin, final String location, final List<EmailSourceHeader> headers,
				final List<EmailSourceProperty> properties, final List<EmailSourcePart> children,
				final String text, final EmailSourceContent content) {
			super(origin, location);
			this.headers = headers;
			this.properties = properties;
			this.children = children;
			this.text = text;
			this.content = content;
		}

		@Override public List<EmailSourceHeader> getHeaders() { return headers; }
		@Override public List<EmailSourceProperty> getProperties() { return properties; }
		@Override public List<EmailSourcePart> getChildren() { return children; }
		@Override public String getText() { return text; }
		@Override public EmailSourceContent getContent() { return content; }
	}

	private static final class Content implements EmailSourceContent {
		private static final long serialVersionUID = 1L;
		private final byte[] bytes;
		private final Representation representation;
		private final boolean fullyCaptured;

		private Content(final byte[] bytes, final Representation representation, final boolean fullyCaptured) {
            this(bytes, representation, fullyCaptured, true);
        }

        private Content(final byte[] bytes, final Representation representation, final boolean fullyCaptured, final boolean copy) {
			this.bytes = copy ? Objects.requireNonNull(bytes, "bytes").clone() : Objects.requireNonNull(bytes, "bytes");
			this.representation = Objects.requireNonNull(representation, "representation");
			this.fullyCaptured = fullyCaptured;
		}

		@Override public Representation getRepresentation() { return representation; }
		@Override public boolean isFullyCaptured() { return fullyCaptured; }
		@Override public long getSize() { return bytes.length; }
		@Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
		@Override public byte[] readAllBytes() { return bytes.clone(); }
	}

	private static final class Source implements EmailSourceData {
		private static final long serialVersionUID = 1L;
		private final EmailSourceIdentity origin;
		private final Format format;
		private final Representation representation;
		private final List<EmailSourceHeader> headers;
		private final List<EmailSourceProperty> properties;
		private final List<EmailSourcePart> parts;
		private final List<EmailSourceData> derivedSources;

		private Source(final EmailSourceIdentity origin, final Format format, final Representation representation,
				final List<EmailSourceHeader> headers, final List<EmailSourceProperty> properties,
				final List<EmailSourcePart> parts, final List<EmailSourceData> derivedSources) {
			this.origin = origin;
			this.format = format;
			this.representation = representation;
			this.headers = headers;
			this.properties = properties;
			this.parts = parts;
			this.derivedSources = derivedSources;
		}

		@Override public EmailSourceIdentity getOrigin() { return origin; }
		@Override public Format getFormat() { return format; }
		@Override public Representation getRepresentation() { return representation; }
		@Override public List<EmailSourceHeader> getHeaders() { return headers; }
		@Override public List<EmailSourceProperty> getProperties() { return properties; }
		@Override public List<EmailSourcePart> getParts() { return parts; }
		@Override public List<EmailSourceData> getDerivedSources() { return derivedSources; }
	}

	private static final class Origin implements EmailSourceIdentity {
		private static final long serialVersionUID = 1L;
		private final UUID reference;
		private final String messageId;

		private Origin(final UUID reference, final String messageId) {
			this.reference = Objects.requireNonNull(reference, "reference");
			this.messageId = messageId;
		}

		@Override public UUID getReference() { return reference; }
		@Override public String getMessageId() { return messageId; }
	}

	private static final class Problem implements EmailProblem {
		private static final long serialVersionUID = 1L;
		private final EmailSourceIdentity origin;
		private final Operation operation;
		private final Code code;
		private final String location;
		private final String value;
		private final String message;
		private final List<EmailSourceElement> sources;

		private Problem(final EmailSourceIdentity origin, final Operation operation, final Code code, final String location,
				final String value, final String message, final List<EmailSourceElement> sources) {
			this.origin = Objects.requireNonNull(origin, "origin");
			this.operation = Objects.requireNonNull(operation, "operation");
			this.code = Objects.requireNonNull(code, "code");
			this.location = Objects.requireNonNull(location, "location");
			this.value = value;
			this.message = Objects.requireNonNull(message, "message");
			this.sources = sources;
		}

		@Override public EmailSourceIdentity getOrigin() { return origin; }
		@Override public Operation getOperation() { return operation; }
		@Override public Code getCode() { return code; }
		@Override public String getLocation() { return location; }
		@Override public String getSourceValue() { return value; }
		@Override public String getMessage() { return message; }
		@Override public List<EmailSourceElement> getSources() { return sources; }
	}
}
