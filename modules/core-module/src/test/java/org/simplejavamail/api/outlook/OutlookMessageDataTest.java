package org.simplejavamail.api.outlook;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class OutlookMessageDataTest {

	@Test
	void legacyConstructorsLeaveSourceBodiesUnknown() {
		final OutlookMessageData old13 = new OutlookMessageData(null, null, null, null, null, null,
				null, null, null, null, null, null, null);
		final OutlookMessageData old14 = new OutlookMessageData(null, null, null, null, null, null,
				null, null, null, null, "modifier", null, null, null);

		assertThat(old13.getBodyHTML()).isNull();
		assertThat(old13.getBodyRTF()).isNull();
		assertThat(old14.getBodyHTML()).isNull();
		assertThat(old14.getBodyRTF()).isNull();
		assertThat(old14.getLastModifierName()).isEqualTo("modifier");
	}

	@Test
	void sourceBodyValuesPreserveNullEmptyAndUnmodifiedText() {
		final String rtf = "{\\rtf1\\ansi\\fromtext text\\par next}";
		final String html = "<html> native\r\nHTML </html>";
		assertThat(sourceBodies(html, rtf).getBodyHTML()).isEqualTo(html);
		assertThat(sourceBodies(html, rtf).getBodyRTF()).isEqualTo(rtf);
		assertThat(sourceBodies("", null).getBodyHTML()).isEmpty();
		assertThat(sourceBodies("", null).getBodyRTF()).isNull();
		assertThat(sourceBodies(null, "").getBodyHTML()).isNull();
		assertThat(sourceBodies(null, "").getBodyRTF()).isEmpty();
	}

	@Test
	void serializedSourceBodiesRoundTripWithoutTheOutlookReader() throws Exception {
		final OutlookMessageData original = sourceBodies("<p>native</p>", "{\\rtf1 source}");
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(original);
		}
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			final OutlookMessageData restored = (OutlookMessageData) in.readObject();
			assertThat(restored.getBodyHTML()).isEqualTo(original.getBodyHTML());
			assertThat(restored.getBodyRTF()).isEqualTo(original.getBodyRTF());
			assertThat(restored.getMessageClass()).isEqualTo("IPM.Note");
			assertThat(restored.getLastModifierName()).isEqualTo("modifier");
		}
		assertThat(ObjectStreamClass.lookup(OutlookMessageData.class).getSerialVersionUID()).isEqualTo(1L);
	}

	@Test
	void published939SnapshotKeepsItsMetadataAndHasUnknownSourceBodies() throws Exception {
		// Produced with the released 9.3.9 core JAR, not a locally installed development artifact.
		try (InputStream fixture = getClass().getResourceAsStream("OutlookMessageData-9.3.9.ser")) {
			assertThat(fixture).isNotNull();
			try (ObjectInputStream in = new ObjectInputStream(fixture)) {
				final OutlookMessageData restored = (OutlookMessageData) in.readObject();
				assertThat(restored.getHeaderValues("received")).containsExactly("hop-one", "hop-two");
				assertThat(restored.getRawHeaders()).isEqualTo("Received: hop-one\r\nReceived: hop-two");
				assertThat(restored.getMessageClass()).isEqualTo("IPM.Note");
				assertThat(restored.getDisplayTo()).isEqualTo("To display");
				assertThat(restored.getDisplayCc()).isEqualTo("Cc display");
				assertThat(restored.getDisplayBcc()).isEqualTo("Bcc display");
				assertThat(restored.getDate()).isEqualTo(new Date(1000));
				assertThat(restored.getClientSubmitTime()).isEqualTo(new Date(2000));
				assertThat(restored.getCreationDate()).isEqualTo(new Date(3000));
				assertThat(restored.getLastModificationDate()).isEqualTo(new Date(4000));
				assertThat(restored.getLastModifierName()).isEqualTo("Legacy modifier");
				assertThat(restored.getPropertiesAsHex()).containsExactly("0x007D", "0x001A");
				assertThat(restored.getPropertyCodes()).containsExactly(125, 26);
				assertThat(restored.getPropertyListing()).isEqualTo("legacy properties");
				assertThat(restored.getBodyHTML()).isNull();
				assertThat(restored.getBodyRTF()).isNull();
			}
		}
	}

	private static OutlookMessageData sourceBodies(String html, String rtf) {
		return new OutlookMessageData(null, null, "IPM.Note", null, null, null, null, null, null, null,
				"modifier", null, null, null, html, rtf);
	}
}
