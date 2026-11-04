package org.simplejavamail.internal.email;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.EmailSourceHeader;
import org.simplejavamail.api.email.EmailSourceIdentity;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.EmailSourceProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailSourceGraphTest {

	@Test
	void capturedReadsAreRepeatableAndCannotExposeMutableStorage() throws Exception {
		final byte[] acquired = { 1, 2, 3 };
		final EmailSourceContent content = EmailInspection.content(acquired, EmailSourceContent.Representation.SOURCE_BYTES, false);
		acquired[0] = 99;
		final byte[] firstRead = content.readAllBytes();
		firstRead[1] = 88;

		try (InputStream first = content.openStream(); InputStream second = content.openStream()) {
			assertThat(first.read()).isEqualTo(1);
			assertThat(second.readAllBytes()).containsExactly(1, 2, 3);
			assertThat(first.readAllBytes()).containsExactly(2, 3);
		}
		assertThat(content.readAllBytes()).containsExactly(1, 2, 3);
		assertThat(content.getSize()).isEqualTo(3);
		assertThat(content.isFullyCaptured()).isFalse();
		assertThat(content.getRepresentation()).isEqualTo(EmailSourceContent.Representation.SOURCE_BYTES);
	}

	@Test
	void completeEmptyPartialAndUnavailableCapturesRemainDistinct() {
		final EmailSourceIdentity origin = EmailInspection.origin(null);
		final EmailSourceContent completeEmpty = EmailInspection.content(new byte[0], EmailSourceContent.Representation.READER_BYTES, true);
		final EmailSourceContent emptyPrefix = EmailInspection.content(new byte[0], EmailSourceContent.Representation.READER_BYTES, false);
		final EmailSourcePart unavailable = leaf(origin, "part[0]", null, null);
		final EmailSourcePart complete = leaf(origin, "part[1]", null, completeEmpty);
		final EmailSourcePart incomplete = leaf(origin, "part[2]", null, emptyPrefix);

		assertThat(unavailable.getContent()).isNull();
		assertThat(complete.getContent().getSize()).isZero();
		assertThat(complete.getContent().isFullyCaptured()).isTrue();
		assertThat(incomplete.getContent().getSize()).isZero();
		assertThat(incomplete.getContent().isFullyCaptured()).isFalse();
	}

	@Test
	void sourceCollectionsKeepOccurrencesAndDetachCallerLists() {
		final EmailSourceIdentity origin = EmailInspection.origin("<reused@example.org>");
		final List<EmailSourceHeader> headers = new ArrayList<>();
		headers.add(EmailInspection.header(origin, "header[0]", "Received", "first"));
		headers.add(EmailInspection.header(origin, "header[1]", "received", null));
		headers.add(EmailInspection.header(origin, "header[2]", "Received", "last"));
		final EmailSourcePart originalRtf = leaf(origin, "body/rtf", "{\\rtf1 Original}", null);
		final List<EmailSourcePart> parts = new ArrayList<>(List.of(originalRtf));
		final EmailSourceData source = EmailInspection.source(origin, EmailSourceData.Format.OUTLOOK_MSG,
				EmailSourceData.Representation.RECEIVED, headers, Collections.emptyList(), parts, Collections.emptyList());
		headers.clear();
		parts.clear();

		assertThat(source.getHeaders()).extracting(EmailSourceHeader::getName).containsExactly("Received", "received", "Received");
		assertThat(source.getHeaders()).extracting(EmailSourceHeader::getRawValue).containsExactly("first", null, "last");
		assertThat(source.getParts()).containsExactly(originalRtf);
		assertThat(source.getParts().get(0).getText()).isEqualTo("{\\rtf1 Original}");
		assertThat(EmailInspection.problems(Collections.emptyList())).isEmpty();
		assertThatThrownBy(() -> source.getHeaders().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> source.getParts().add(originalRtf)).isInstanceOf(UnsupportedOperationException.class);
		assertThat(EmailInspection.origin("<reused@example.org>").getReference()).isNotEqualTo(origin.getReference());
	}

	@Test
	void copyingAndQualifyingKeepTheExactCapturedOccurrencesWithoutAccumulation() {
		final EmailSourceIdentity origin = EmailInspection.origin("<source@example.org>");
		final EmailSourceHeader header = EmailInspection.header(origin, "header[0]", "Subject", "=?unknown?q?text?=");
		final EmailSourcePart failed = leaf(origin, "part[0]", null,
				EmailInspection.content(new byte[] { 4, 5 }, EmailSourceContent.Representation.SOURCE_BYTES, false));
		final EmailSourceData source = source(origin, List.of(header), List.of(failed));
		final EmailProblem problem = EmailInspection.problem(origin, EmailProblem.Operation.IMPORT,
				EmailProblem.Code.CONTENT_READ_FAILED, failed.getLocation(), null, "Retained prefix", List.of(header, failed));
		final List<EmailProblem> findings = new ArrayList<>(Arrays.asList(problem, problem));
		final List<EmailProblem> snapshot = EmailInspection.problems(findings);
		findings.clear();

		assertThat(EmailInspection.copyOf(source)).isSameAs(source);
		assertThat(snapshot).containsExactly(problem, problem);
		assertThat(snapshot.get(0).getSources().get(0)).isSameAs(source.getHeaders().get(0));
		assertThat(snapshot.get(0).getSources().get(1)).isSameAs(source.getParts().get(0));
		assertThat(snapshot.get(0).getOrigin()).isSameAs(source.getOrigin());
		final EmailProblem qualified = EmailInspection.qualify(problem, "source/derived[0]/");
		assertThat(qualified.getLocation()).isEqualTo("source/derived[0]/part[0]");
		assertThat(qualified.getSources().get(1)).isSameAs(failed);
		assertThat(qualified.getOrigin().getReference()).isEqualTo(origin.getReference());
		assertThatThrownBy(() -> qualified.getSources().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void serializationPreservesProblemLinksAndSourceSharingWithoutAnotherAcquisition() throws Exception {
		final EmailSourceIdentity origin = EmailInspection.origin("<same-id@example.org>");
		final AtomicInteger acquisitions = new AtomicInteger();
		final EmailSourceContent supplied = new EmailSourceContent() {
			@Override public Representation getRepresentation() { return Representation.READER_BYTES; }
			@Override public boolean isFullyCaptured() { return false; }
			@Override public long getSize() { return 2; }
			@Override public InputStream openStream() { throw new AssertionError("No source callback should be retained"); }
			@Override public byte[] readAllBytes() { acquisitions.incrementAndGet(); return new byte[] { 7, 8 }; }
		};
		final EmailSourcePart failed = leaf(origin, "part[0]", null, supplied);
		assertThat(acquisitions.get()).isEqualTo(1);
		final EmailSourceData source = source(origin, Collections.emptyList(), List.of(failed));
		final EmailProblem problem = EmailInspection.problem(origin, EmailProblem.Operation.IMPORT,
				EmailProblem.Code.CONTENT_READ_FAILED, failed.getLocation(), null, "Reader stopped", List.of(failed));

		final Object[] restored = restore(new Object[] { source, EmailInspection.problems(List.of(problem)) });
		final EmailSourceData restoredSource = (EmailSourceData) restored[0];
		final EmailProblem restoredProblem = ((List<EmailProblem>) restored[1]).get(0);
		assertThat(restoredProblem.getSources().get(0)).isSameAs(restoredSource.getParts().get(0));
		assertThat(restoredProblem.getOrigin()).isSameAs(restoredSource.getOrigin());
		assertThat(restoredProblem.getOrigin().getReference()).isEqualTo(origin.getReference());
		assertThat(restoredSource.getParts().get(0).getContent().readAllBytes()).containsExactly(7, 8);
		assertThat(acquisitions.get()).isEqualTo(1);
		assertThat(EmailInspection.copyOf(restoredSource)).isSameAs(restoredSource);
	}

	@Test
	void detachingForeignMetadataKeepsSharedOccurrencesAndOwnsMutableInputs() {
		final EmailSourceIdentity origin = EmailInspection.origin(null);
		final String[] value = { "before" };
		final EmailSourceHeader mutableHeader = new EmailSourceHeader() {
			@Override public EmailSourceIdentity getOrigin() { return origin; }
			@Override public String getLocation() { return "header[0]"; }
			@Override public String getName() { return "X-Original"; }
			@Override public String getRawValue() { return value[0]; }
		};
		final List<EmailSourceHeader> headers = new ArrayList<>(List.of(mutableHeader));
		final EmailSourcePart suppliedPart = new EmailSourcePart() {
			@Override public EmailSourceIdentity getOrigin() { return origin; }
			@Override public String getLocation() { return "part[0]"; }
			@Override public List<EmailSourceHeader> getHeaders() { return headers; }
			@Override public List<EmailSourceProperty> getProperties() { return Collections.emptyList(); }
			@Override public List<EmailSourcePart> getChildren() { return Collections.emptyList(); }
			@Override public String getText() { return null; }
			@Override public EmailSourceContent getContent() { return null; }
		};
		final EmailSourceData supplied = new EmailSourceData() {
			@Override public EmailSourceIdentity getOrigin() { return origin; }
			@Override public Format getFormat() { return Format.MIME; }
			@Override public Representation getRepresentation() { return Representation.RECEIVED; }
			@Override public List<EmailSourceHeader> getHeaders() { return headers; }
			@Override public List<EmailSourceProperty> getProperties() { return Collections.emptyList(); }
			@Override public List<EmailSourcePart> getParts() { return List.of(suppliedPart); }
			@Override public List<EmailSourceData> getDerivedSources() { return Collections.emptyList(); }
		};

		final EmailSourceData detached = EmailInspection.copyOf(supplied);
		value[0] = "after";
		headers.clear();
		assertThat(detached.getHeaders().get(0).getRawValue()).isEqualTo("before");
		assertThat(detached.getParts().get(0).getHeaders().get(0)).isSameAs(detached.getHeaders().get(0));
		assertThat(detached.getOrigin().getMessageId()).isNull();
		assertThat(detached.getParts().get(0).getProperties()).isEmpty();
	}

	@Test
	void emptyInspectionDoesNotInventInputIdentityOrExposeNullCollections() {
		final EmailSourceData empty = EmailInspection.copyOf(null);
		assertThat(empty.getOrigin()).isNull();
		assertThat(empty.getFormat()).isNull();
		assertThat(empty.getRepresentation()).isNull();
		assertThat(empty.getHeaders()).isEmpty();
		assertThat(empty.getProperties()).isEmpty();
		assertThat(empty.getParts()).isEmpty();
		assertThat(empty.getDerivedSources()).isEmpty();
		assertThat(EmailInspection.problems(null)).isEmpty();
	}

	private static EmailSourcePart leaf(final EmailSourceIdentity origin, final String location, final String text,
			final EmailSourceContent content) {
		return EmailInspection.part(origin, location, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), text, content);
	}

	private static EmailSourceData source(final EmailSourceIdentity origin, final List<EmailSourceHeader> headers,
			final List<EmailSourcePart> parts) {
		return EmailInspection.source(origin, EmailSourceData.Format.MIME, EmailSourceData.Representation.RECEIVED, headers,
				Collections.emptyList(), parts, Collections.emptyList());
	}

	private static Object[] restore(final Object[] graph) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(graph);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (Object[]) input.readObject();
		}
	}
}
