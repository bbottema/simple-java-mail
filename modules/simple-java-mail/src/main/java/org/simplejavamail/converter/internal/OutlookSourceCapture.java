package org.simplejavamail.converter.internal;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceIdentity;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.EmailSourceProperty;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookAttachment;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookFileAttachment;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.internal.email.EmailInspection;
import org.simplejavamail.internal.email.InspectionOutlookData;
import org.simplejavamail.converter.internal.mimemessage.InputInspection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/** Native reader values detached from the reader; no generated MIME is presented as the original MSG. */
final class OutlookSourceCapture {

	private OutlookSourceCapture() { }

	static List<EmailSourcePart> originalHtmlParts(final EmailSourceData source, final OutlookMessage message) {
		if (message.getBodyHTML() == null) return emptyList();
		for (EmailSourcePart part : source.getParts()) {
			if (part.getProperties().stream().anyMatch(property -> property.getName().equals("bodyFormat") && "html".equals(property.getValue()))) {
				return singletonList(EmailInspection.part(part.getOrigin(), part.getLocation(), part.getHeaders(), part.getProperties(),
						part.getChildren(), message.getBodyHTML(), null));
			}
		}
		return emptyList();
	}

	static EmailSourceData capture(final EmailSourceData metadata, final OutlookMessage message, final EmailPopulatingBuilder represented,
			final List<EmailProblem> problems) {
		return capture(metadata, message, represented, problems, java.util.Collections.emptyMap());
	}

	static EmailSourceData capture(final EmailSourceData metadata, final OutlookMessage message, final EmailPopulatingBuilder represented,
			final List<EmailProblem> problems, final Map<Integer, String> attachmentFailures) {
		final EmailSourceIdentity origin = metadata.getOrigin();
		final List<EmailSourcePart> parts = new ArrayList<>();
		final Email merged = represented != null && represented.isMergeSingleSMIMESignedAttachment() ? represented.getSmimeSignedEmail() : null;
		body(parts, origin, "plain", message.getBodyText(), merged != null ? merged.getPlainText() : represented != null ? represented.getPlainText() : null);
		body(parts, origin, "html", message.getBodyHTML(), merged != null ? merged.getHTMLText() : represented != null ? represented.getHTMLText() : null);
		// RTF is not exposed by a normal Email body getter. Its original reader text survives a successful HTML conversion.
		body(parts, origin, "rtf", message.getBodyRTF(), null);
		final List<OutlookAttachment> nativeAttachments = message.getOutlookAttachments();
		for (int attachmentIndex = 0; attachmentIndex < nativeAttachments.size(); attachmentIndex++) {
			final OutlookAttachment attachment = nativeAttachments.get(attachmentIndex);
			final String location = "source/parts[" + parts.size() + "]";
			final List<EmailSourceProperty> properties = new ArrayList<>();
			List<EmailSourcePart> children = emptyList();
			if (attachment instanceof OutlookFileAttachment) {
				final OutlookFileAttachment file = (OutlookFileAttachment) attachment;
				property(properties, origin, location, "filename", "String", file.getFilename());
				property(properties, origin, location, "longFilename", "String", file.getLongFilename());
				property(properties, origin, location, "mimeTag", "String", file.getMimeTag());
				property(properties, origin, location, "extension", "String", file.getExtension());
				property(properties, origin, location, "contentId", "String", file.getContentId());
				property(properties, origin, location, "size", "Long", Long.toString(file.getSize()));
				// The normal attachment/image already owns the canonical file bytes. Do not retain another copy here.
			} else if (attachment.getEmbeddedMessage() != null) {
				property(properties, origin, location, "kind", "String", "embedded-message");
				final OutlookMessage nested = attachment.getEmbeddedMessage();
				final InspectionOutlookData nativeData = InspectionOutlookData.builder().rawHeaders(nested.getHeaders())
						.messageClass(nested.getMessageClass()).propertiesAsHex(nested.getPropertiesAsHex())
						.propertyCodes(nested.getPropertyCodes()).build();
				final EmailSourceData nestedMetadata = InputInspection.capture(nativeData, nested.getMessageId()).getSourceData();
				final List<EmailProblem> nestedProblems = new ArrayList<>();
				final EmailSourceData nestedSource = capture(nestedMetadata, nested, null, nestedProblems);
				children = singletonList(EmailInspection.part(nestedSource.getOrigin(), "source/message", nestedSource.getHeaders(),
						nestedSource.getProperties(), nestedSource.getParts(), null, null));
				for (EmailProblem problem : nestedProblems) {
					problems.add(EmailInspection.qualify(problem, location + "/message/"));
				}
			} else {
				property(properties, origin, location, "kind", "String", "reader-resource");
			}
			final EmailSourcePart source = EmailInspection.part(origin, location, emptyList(), properties, children, null, null);
			parts.add(source);
			if (attachmentFailures.containsKey(attachmentIndex)) {
				problems.add(EmailInspection.problem(origin, EmailProblem.Operation.IMPORT, EmailProblem.Code.PROTECTION_FAILED,
						location, null, attachmentFailures.get(attachmentIndex), singletonList(source)));
			}
		}
		final List<EmailSourceProperty> properties = new ArrayList<>(metadata.getProperties());
		for (Integer code : message.getPropertyCodes()) {
			final Object value = message.getPropertyValue(code);
			final String location = "source/properties[" + properties.size() + "]";
			final String name = String.format(Locale.ROOT, "0x%04X", code);
			// Body bytes/text are accounted for by the native body parts above. A property-listing string must not duplicate them.
			final boolean body = code == 0x1000 || code == 0x1009 || code == 0x1013;
			final boolean interpretedBody = code == 0x1000 ? message.getBodyText() != null
					: code == 0x1009 ? message.getBodyRTF() != null : code == 0x1013 && message.getBodyHTML() != null;
			if (body && !interpretedBody && value != null) {
				final String bodyLocation = "source/parts[" + parts.size() + "]";
				final List<EmailSourceProperty> bodyProperties = new ArrayList<>();
				property(bodyProperties, origin, bodyLocation, "bodyFormat", "String", code == 0x1009 ? "rtf" : code == 0x1013 ? "html" : "plain");
				property(bodyProperties, origin, bodyLocation, "nativeProperty", "String", name);
				final EmailSourceContent original = value instanceof byte[]
						? EmailInspection.content((byte[]) value, EmailSourceContent.Representation.READER_BYTES, true) : null;
				final EmailSourcePart unreadableBody = EmailInspection.part(origin, bodyLocation, emptyList(), bodyProperties, emptyList(),
						value instanceof String ? (String) value : null, original);
				parts.add(unreadableBody);
				problems.add(EmailInspection.problem(origin, EmailProblem.Operation.IMPORT, EmailProblem.Code.FIELD_NOT_INTERPRETED,
						bodyLocation, null, "The native " + name + " body was available but could not be interpreted by its reader",
						singletonList(unreadableBody)));
			}
			final EmailSourceContent content = !body && value instanceof byte[]
					? EmailInspection.content((byte[]) value, EmailSourceContent.Representation.READER_BYTES, true) : null;
			final String scalar = !body && (value instanceof String || value instanceof Number || value instanceof Boolean)
					? String.valueOf(value) : !body && value instanceof Date ? Long.toString(((Date) value).getTime()) : null;
			properties.add(EmailInspection.property(origin, location, name, value == null ? null : value.getClass().getSimpleName(), scalar, content));
		}
		return EmailInspection.withProperties(EmailInspection.withParts(metadata, parts), properties);
	}

	private static void body(final List<EmailSourcePart> parts, final EmailSourceIdentity origin, final String kind,
			final String original, final String represented) {
		if (original == null) return;
		final String location = "source/parts[" + parts.size() + "]";
		final List<EmailSourceProperty> properties = new ArrayList<>();
		property(properties, origin, location, "bodyFormat", "String", kind);
		parts.add(EmailInspection.part(origin, location, emptyList(), properties, emptyList(),
				Objects.equals(original, represented) ? null : original, null));
	}

	private static void property(final List<EmailSourceProperty> values, final EmailSourceIdentity origin, final String parent,
			final String name, final String type, final String value) {
		if (value != null) {
			values.add(EmailInspection.property(origin, parent + "/properties[" + values.size() + "]", name, type, value, null));
		}
	}
}
