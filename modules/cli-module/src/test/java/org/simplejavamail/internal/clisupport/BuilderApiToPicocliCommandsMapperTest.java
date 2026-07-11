package org.simplejavamail.internal.clisupport;

import jakarta.mail.Message;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.OpenConnectionCallback;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.colorizeDescriptions;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.getArgumentsForCliOption;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.methodIsCliCompatible;

public class BuilderApiToPicocliCommandsMapperTest {

	private static final List<String> BUILDER_API_SOURCE_FILES = Arrays.asList(
			"org/simplejavamail/api/email/EmailStartingBuilder.java",
			"org/simplejavamail/api/email/EmailPopulatingBuilder.java",
			"org/simplejavamail/api/mailer/MailerGenericBuilder.java",
			"org/simplejavamail/api/mailer/MailerRegularBuilder.java",
			"org/simplejavamail/api/mailer/MailerFromSessionBuilder.java");
	
	@Test
	public void testColorizeDescriptions() {
		assertThat(colorizeDescriptions(singletonList("nothing to colorize"))).containsExactly("nothing to colorize");
		assertThat(colorizeDescriptions(singletonList("one --x:item to colorize"))).containsExactly("one @|cyan --x:item|@ to colorize");
		assertThat(colorizeDescriptions(singletonList("item @|--x:already|@ colorized"))).containsExactly("item @|--x:already|@ colorized");
		assertThat(colorizeDescriptions(singletonList("@|item @|--x:already|@ c|@olorized"))).containsExactly("@|item @|--x:already|@ c|@olorized");
		assertThat(colorizeDescriptions(singletonList("@|one @|--x:item|@ --x:to|@--x:colorize"))).containsExactly("@|one @|--x:item|@ --x:to|@@|cyan --x:colorize|@");
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyClosed() {
		final List<String> strings = new ArrayList<>();
		strings.add("@| |@ |@ @| |@");
		
		assertThatThrownBy(() -> colorizeDescriptions(strings))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("closed token without open token");
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyOpened() {
		final List<String> strings = new ArrayList<>();
		strings.add("@| |@ @| @| |@");
		
		assertThatThrownBy(() -> colorizeDescriptions(strings))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("open token without closed token");
	}
	
	@Test
	public void testExtractJavadocDescription_extractJavadocExamples() {
		final String description = "Single RFC2822 address or delimited list of RFC2822 addresses of TO receiver(s). Any names included are ignored if a name was provided.";
		final String examples = " Examples:  \n" +
				"  - lolly.pop@pretzelfun.com \n" +
				"  - Lolly Pop<lolly.pop@pretzelfun.com> \n" +
				"  - a1@b1.c1,a2@b2.c2,a3@b3.c3 \r\n" +
				"  - a1@b1.c1;a2@b2.c2;a3@b3.c3 ";
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description + examples)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description)).isEqualTo(new String[0]);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description + examples)).containsExactly(
				"lolly.pop@pretzelfun.com",
				"Lolly Pop<lolly.pop@pretzelfun.com>",
				"a1@b1.c1,a2@b2.c2,a3@b3.c3",
				"a1@b1.c1;a2@b2.c2;a3@b3.c3");
	}
	
	@Test
	public void testExtractJavadocDescription_extractJavadocExample() {
		final String description = "Single RFC2822 address or delimited list of RFC2822 addresses of TO receiver(s). Any names included are ignored if a name was provided.";
		final String example = " Example: lolly.pop@pretzelfun.com \n";
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description + example)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description)).isEqualTo(new String[0]);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description + example)).containsExactly("lolly.pop@pretzelfun.com");
	}

	@Test
	public void deliveryStatusNotificationBuilderApiKeepsOnlyStringMethodsCliCompatible() throws Exception {
		assertThat(methodIsCliCompatible(EmailPopulatingBuilder.class.getMethod("withDeliveryStatusNotificationNotifyOptions", String.class)).isCompatible()).isTrue();
		assertThat(methodIsCliCompatible(EmailPopulatingBuilder.class.getMethod("withDeliveryStatusNotificationReturnOption", String.class)).isCompatible()).isTrue();
		assertThat(methodIsCliCompatible(EmailPopulatingBuilder.class.getMethod("withDeliveryStatusNotification", DeliveryStatusNotification.class)).isCompatible()).isFalse();
		assertThat(methodIsCliCompatible(EmailPopulatingBuilder.class.getMethod("withDeliveryStatusNotificationNotifyOptions", DeliveryStatusNotification.NotifyOption[].class)).isCompatible()).isFalse();
		assertThat(methodIsCliCompatible(EmailPopulatingBuilder.class.getMethod("withDeliveryStatusNotificationReturnOption", DeliveryStatusNotification.ReturnOption.class)).isCompatible()).isFalse();
	}

	@Test
	public void cliOptionalParametersAreMappedToOptionalArguments() throws Exception {
		Method from = EmailPopulatingBuilder.class.getMethod("from", String.class, String.class);
		assertThat(getArgumentsForCliOption(from)).extracting("required").containsExactly(false, true);

		Method withProxy = MailerGenericBuilder.class.getMethod("withProxy", String.class, Integer.class);
		assertThat(hasCliOptionalParameter(withProxy, 0)).isTrue();
		assertThat(hasCliOptionalParameter(withProxy, 1)).isTrue();

		Method withSmtpClientHostname = MailerGenericBuilder.class.getMethod("withSmtpClientHostname", String.class);
		assertThat(methodIsCliCompatible(withSmtpClientHostname).isCompatible()).isTrue();
		assertThat(hasCliOptionalParameter(withSmtpClientHostname, 0)).isTrue();
	}

	@Test
	public void stringVarargsBuilderApiIsMappedAndConvertedForCli() throws Exception {
		Method withRecipients = EmailPopulatingBuilder.class.getMethod("withRecipients", String.class, boolean.class, Message.RecipientType.class, String[].class);
		assertThat(methodIsCliCompatible(withRecipients).isCompatible()).isTrue();
		assertThat(getArgumentsForCliOption(withRecipients)).extracting("helpLabel")
				.containsExactly("TEXT", "BOOL", "NAME", "TEXT");

		List<CliDeclaredOptionSpec> declaredOptions = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(
				new Class<?>[] { EmailStartingBuilder.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class });
		assertThat(declaredOptions).extracting(CliDeclaredOptionSpec::getName)
				.contains("--email:withRecipients");

		List<Object> fullValues = CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(Arrays.asList(
				"Team", "false", "TO", "alice@example.com;bob@example.com")), withRecipients);
		assertThat(fullValues.get(0)).isEqualTo("Team");
		assertThat(fullValues.get(1)).isEqualTo(false);
		assertThat(fullValues.get(2)).isEqualTo(Message.RecipientType.TO);
		assertThat((String[]) fullValues.get(3)).containsExactly("alice@example.com", "bob@example.com");

		List<Object> minimalValues = CliCommandLineConsumer.convertProvidedOptionValues(new ArrayList<>(Arrays.asList(
				"false", "alice@example.com,bob@example.com")), withRecipients);
		assertThat(minimalValues.get(0)).isNull();
		assertThat(minimalValues.get(1)).isEqualTo(false);
		assertThat(minimalValues.get(2)).isNull();
		assertThat((String[]) minimalValues.get(3)).containsExactly("alice@example.com", "bob@example.com");
	}

	@Test
	public void mailerFacadeSendApisAreNotGeneratedAsCliBuilderOption() throws Exception {
		Method simpleBatchSend = Mailer.class.getMethod("sendMailsInSimpleBatch", Iterable.class);
		assertThat(methodIsCliCompatible(simpleBatchSend).isCompatible()).isFalse();
		assertThat(methodIsCliCompatible(simpleBatchSend).getReason()).contains("@BuilderApiNode missing");
		Method openConnectionSend = Mailer.class.getMethod("withOpenConnection", OpenConnectionCallback.class);
		assertThat(methodIsCliCompatible(openConnectionSend).isCompatible()).isFalse();
		assertThat(methodIsCliCompatible(openConnectionSend).getReason()).contains("@BuilderApiNode missing");

		List<CliDeclaredOptionSpec> declaredOptions = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(
				new Class<?>[] { EmailStartingBuilder.class, MailerRegularBuilder.class, MailerFromSessionBuilder.class });
		assertThat(declaredOptions).extracting(CliDeclaredOptionSpec::getName)
				.doesNotContain("--mailer:sendMailsInSimpleBatch", "--mailer:withOpenConnection");
	}

	@Test
	public void nullableParametersOnBuilderApisDeclareCliOptional() throws IOException {
		List<String> violations = new ArrayList<>();
		for (String builderApiSourceFile : BUILDER_API_SOURCE_FILES) {
			List<String> sourceLines = Files.readAllLines(resolveCoreModuleSource(builderApiSourceFile), UTF_8);
			for (int lineNumber = 0; lineNumber < sourceLines.size(); lineNumber++) {
				String sourceLine = sourceLines.get(lineNumber);
				if (hasNullableParameterWithoutCliOptional(sourceLine)) {
					violations.add(builderApiSourceFile + ":" + (lineNumber + 1) + ": " + sourceLine.trim());
				}
			}
		}

		assertThat(violations).isEmpty();
	}

	private static boolean hasCliOptionalParameter(Method method, int parameterIndex) {
		for (Annotation annotation : method.getParameterAnnotations()[parameterIndex]) {
			if (annotation.annotationType() == Cli.Optional.class) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasNullableParameterWithoutCliOptional(String sourceLine) {
		int nullableIndex = sourceLine.indexOf("@Nullable");
		while (nullableIndex >= 0) {
			int openParenIndex = sourceLine.lastIndexOf('(', nullableIndex);
			int commaIndex = sourceLine.lastIndexOf(',', nullableIndex);
			if (Math.max(openParenIndex, commaIndex) >= 0 && !sourceLine.substring(nullableIndex).startsWith("@Nullable @Cli.Optional")) {
				return true;
			}
			nullableIndex = sourceLine.indexOf("@Nullable", nullableIndex + 1);
		}
		return false;
	}

	private static Path resolveCoreModuleSource(String builderApiSourceFile) {
		Path sourceFile = Paths.get("..", "core-module", "src", "main", "java").resolve(builderApiSourceFile);
		if (!Files.exists(sourceFile)) {
			sourceFile = Paths.get("modules", "core-module", "src", "main", "java").resolve(builderApiSourceFile);
		}
		return sourceFile;
	}
}
