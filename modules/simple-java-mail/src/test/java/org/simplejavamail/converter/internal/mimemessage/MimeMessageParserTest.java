package org.simplejavamail.converter.internal.mimemessage;

import org.junit.Test;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.ParsedMimeMessageComponents;

import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.converter.internal.mimemessage.MimeMessageParser.moveInvalidEmbeddedResourcesToAttachments;

public class MimeMessageParserTest {

	@Test
	public void testMoveInvalidEmbeddedResourcesToAttachments_NoHtmlNoInvalid() throws IOException {
		ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
		parsedComponents.cidMap.put("moo1", new ByteArrayDataSource("moomoo", "text/plain"));
		parsedComponents.cidMap.put("moo2", new ByteArrayDataSource("moomoo", "text/plain"));
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		
		assertThat(parsedComponents.cidMap).isEmpty();
		assertThat(parsedComponents.attachmentList).containsOnlyKeys("moo1","moo2");
	}

	@Test
	public void testMoveInvalidEmbeddedResourcesToAttachments_HtmlButNoInvalid() throws IOException {
		ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
		parsedComponents.htmlContent.append("blah moo1 blah html");
		parsedComponents.cidMap.put("moo1", new ByteArrayDataSource("moomoo", "text/plain"));
		parsedComponents.cidMap.put("moo2", new ByteArrayDataSource("moomoo", "text/plain"));
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		
		assertThat(parsedComponents.cidMap).isEmpty();
		assertThat(parsedComponents.attachmentList).containsOnlyKeys("moo1","moo2");
	}
	
	@Test
	public void testMoveInvalidEmbeddedResourcesToAttachments_Invalid() throws IOException {
		ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
		parsedComponents.htmlContent.append("blah cid:moo1 blah html");
		parsedComponents.cidMap.put("moo1", new ByteArrayDataSource("moomoo", "text/plain"));
		parsedComponents.cidMap.put("moo2", new ByteArrayDataSource("moomoo", "text/plain"));
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		
		assertThat(parsedComponents.cidMap).containsOnlyKeys("moo1");
		assertThat(parsedComponents.attachmentList).containsOnlyKeys("moo2");
	}
}