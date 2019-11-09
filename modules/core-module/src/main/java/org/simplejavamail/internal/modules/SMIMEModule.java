package org.simplejavamail.internal.modules;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.smimesupport.builder.SmimeParseResult;
import org.simplejavamail.api.internal.smimesupport.model.SmimeDetails;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimePart;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * This interface only serves to hide the S/MIME implementation behind an easy-to-load-with-reflection class.
 */
public interface SMIMEModule {

	String NAME = "S/MIME module";

	/**
	 * @return The results of the S/MIME decryption of any compatible encrypted / signed attachments.
	 */
	SmimeParseResult decryptAttachments(@NotNull List<AttachmentResource> attachments, @NotNull OutlookMessage outlookMessage, @Nullable Pkcs12Config pkcs12Config);

	/**
	 * @return The results of the S/MIME decryption of any compatible encrypted / signed attachments.
	 */
	SmimeParseResult decryptAttachments(@NotNull List<AttachmentResource> attachments, @NotNull MimeMessage mimeMessage, @Nullable Pkcs12Config pkcs12Config);

	/**
	 * @return A copy of given original 'true' attachments, with S/MIME encrypted / signed attachments replaced with the actual attachment.
	 */
	@NotNull
	List<AttachmentResource> decryptAttachments(@NotNull List<AttachmentResource> attachments, @Nullable Pkcs12Config pkcs12Config, @NotNull OriginalSmimeDetails messageSmimeDetails);

	/**
	 * @return Whether the given attachment is S/MIME signed / encrypted. Defers to {@code SmimeRecognitionUtil.isSmimeAttachment(..)}.
	 */
	boolean isSmimeAttachment(@NotNull AttachmentResource attachment);

	/**
	 * @return The S/MIME mime type and signed who signed the attachment.
	 * <br>
	 * <strong>Note:</strong> the attachment is assumed to be a signed / encrypted {@link javax.mail.internet.MimeBodyPart}.
	 */
	@NotNull
	SmimeDetails getSmimeDetails(@NotNull AttachmentResource attachment);

	/**
	 * Delegates to {@link #getSignedByAddress(MimePart)}, where the datasource of the attachment is read completely as a MimeMessage.
	 * <br>
	 * <strong>Note:</strong> the attachment is assumed to be a signed / encrypted {@link javax.mail.internet.MimeBodyPart}.
	 */
	@Nullable
	String getSignedByAddress(@NotNull AttachmentResource smimeAttachment);

	/**
	 * @return Who S/MIME signed /encrypted the attachment. This is indicated by the subject of the certificate (whom the certificate was 'issued to').
	 */
	@Nullable
	String getSignedByAddress(@NotNull MimePart mimePart);

	boolean verifyValidSignature(@NotNull MimeMessage mimeMessage, @NotNull OriginalSmimeDetails messageSmimeDetails);

	@NotNull
	MimeMessage signAndOrEncryptEmail(@NotNull final Session session, @NotNull final MimeMessage messageToProtect, @NotNull final Email emailContainingSmimeDetails);

	@NotNull
	MimeMessage signMessage(@Nullable Session session, @NotNull MimeMessage message, @NotNull Pkcs12Config pkcs12Config);

	@NotNull
	MimeMessage encryptMessage(@Nullable Session session, @NotNull MimeMessage message, @NotNull X509Certificate certificate);
}
