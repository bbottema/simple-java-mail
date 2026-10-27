package org.simplejavamail.internal.clisupport.valueinterpreters;

import jakarta.mail.internet.MimeMessage;
import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.converter.EmailConverter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/** One registered conversion owns both message formats; converter registration order must not choose the parser. */
public final class MessageFilePathToMimeMessageFunction extends FileBasedFunction<MimeMessage> {

	private static final long COMPOUND_DOCUMENT_SIGNATURE = 0xD0CF11E0A1B11AE1L;

	@Override
	public Class<String> getFromType() {
		return String.class;
	}

	@Override
	public Class<MimeMessage> getTargetType() {
		return MimeMessage.class;
	}

	@NotNull
	@Override
	protected MimeMessage convertFile(final File messageFile) {
		return hasCompoundDocumentSignature(messageFile) ? EmailConverter.outlookMsgToMimeMessage(messageFile) : EmailConverter.emlToMimeMessage(messageFile);
	}

	/** Inspect the compound-file signature so extensionless and renamed MSG files retain their format. */
	private static boolean hasCompoundDocumentSignature(final File messageFile) {
		try (InputStream input = Files.newInputStream(messageFile.toPath())) {
			final byte[] signature = input.readNBytes(Long.BYTES);
			return signature.length == Long.BYTES && ByteBuffer.wrap(signature).getLong() == COMPOUND_DOCUMENT_SIGNATURE;
		} catch (IOException failure) {
			throw new IncompatibleTypeException(messageFile, String.class, MimeMessage.class, failure);
		}
	}
}
