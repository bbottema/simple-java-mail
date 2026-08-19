package org.simplejavamail.internal.smimesupport;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.smime.SMIMECapabilitiesAttribute;
import org.bouncycastle.asn1.smime.SMIMECapability;
import org.bouncycastle.asn1.smime.SMIMECapabilityVector;
import org.bouncycastle.asn1.smime.SMIMEEncryptionKeyPreferenceAttribute;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSAlgorithm;
import org.bouncycastle.cms.RecipientInformation;
import org.bouncycastle.cms.RecipientInformationStore;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientId;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.mail.smime.SMIMEEnveloped;
import org.bouncycastle.mail.smime.SMIMEEnvelopedGenerator;
import org.bouncycastle.mail.smime.SMIMESigned;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.bouncycastle.mail.smime.SMIMEUtil;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.jcajce.JcaAlgorithmParametersConverter;
import org.bouncycastle.util.Store;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.utils.mail.smime.KeyEncapsulationAlgorithm;
import org.simplejavamail.utils.mail.smime.SmimeKey;
import org.simplejavamail.utils.mail.smime.SmimeState;

import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * The S/MIME operations used by the module, with a private Bouncy Castle provider instance passed to each operation.
 *
 * <p>This deliberately does not register a security provider or change Jakarta Activation's default command map.</p>
 */
final class SmimeCryptoSupport {

	static final String DEFAULT_SIGNATURE_ALGORITHM_NAME = "SHA256withRSA";
	static final KeyEncapsulationAlgorithm DEFAULT_KEY_ENCAPSULATION_ALGORITHM = KeyEncapsulationAlgorithm.RSA;
	static final ASN1ObjectIdentifier DEFAULT_CIPHER = CMSAlgorithm.DES_EDE3_CBC;
	static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();

	private SmimeCryptoSupport() {
	}

	@NotNull
	static MimeBodyPart encrypt(@NotNull final MimeBodyPart mimeBodyPart,
			@NotNull final X509Certificate certificate,
			@NotNull final KeyEncapsulationAlgorithm keyEncapsulationAlgorithm,
			@NotNull final ASN1ObjectIdentifier cmsAlgorithm) {
		try {
			final SMIMEEnvelopedGenerator generator = new SMIMEEnvelopedGenerator();
			generator.addRecipientInfoGenerator(createRecipientInfoGenerator(certificate, keyEncapsulationAlgorithm));
			final OutputEncryptor encryptor = new JceCMSContentEncryptorBuilder(cmsAlgorithm)
					.setProvider(BOUNCY_CASTLE)
					.build();
			return generator.generate(mimeBodyPart, encryptor);
		} catch (Exception e) {
			throw handledException("Error encrypting S/MIME content", e);
		}
	}

	@NotNull
	static MimeBodyPart decrypt(@NotNull final MimeBodyPart mimeBodyPart, @NotNull final SmimeKey smimeKey) {
		try {
			final SMIMEEnveloped enveloped = new SMIMEEnveloped(mimeBodyPart);
			final RecipientInformationStore recipients = enveloped.getRecipientInfos();
			final RecipientInformation recipient = recipients.get(new JceKeyTransRecipientId(smimeKey.getCertificate()));
			if (recipient == null) {
				throw new MessagingException("The supplied S/MIME key is not a recipient of this message");
			}
			final JceKeyTransEnvelopedRecipient transportRecipient =
					new JceKeyTransEnvelopedRecipient(smimeKey.getPrivateKey());
			transportRecipient.setProvider(BOUNCY_CASTLE);
			return SMIMEUtil.toMimeBodyPart(recipient.getContent(transportRecipient));
		} catch (Exception e) {
			throw handledException("Error decrypting S/MIME content", e);
		}
	}

	@NotNull
	static MimeBodyPart sign(@NotNull final MimeBodyPart mimeBodyPart,
			@NotNull final SmimeKey smimeKey,
			@NotNull final String algorithmName) {
		try {
			final SMIMESignedGenerator generator = new SMIMESignedGenerator();
			generator.addCertificates(certificateStore(smimeKey));
			generator.addSignerInfoGenerator(new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder()
					.setProvider(BOUNCY_CASTLE)
					.setSignedAttributeGenerator(new AttributeTable(signedAttributes(smimeKey)))
					.build(algorithmName, smimeKey.getPrivateKey(), smimeKey.getCertificate()));

			final MimeMultipart signedMultipart = generator.generate(canonicalize(mimeBodyPart));
			final MimeBodyPart signedBodyPart = new MimeBodyPart();
			signedBodyPart.setContent(signedMultipart);
			return signedBodyPart;
		} catch (Exception e) {
			throw handledException("Error signing S/MIME content", e);
		}
	}

	static boolean checkSignature(@NotNull final MimePart mimePart) {
		try {
			final SMIMESigned signed = signed(mimePart);
			@SuppressWarnings("rawtypes")
			final Store certificates = signed.getCertificates();
			final Iterator<SignerInformation> signers = signed.getSignerInfos().getSigners().iterator();
			while (signers.hasNext()) {
				final SignerInformation signer = signers.next();
				final X509Certificate certificate = certificate(certificates, signer);
				final SignerInformationVerifier verifier = new JcaSimpleSignerInfoVerifierBuilder()
						.setProvider(BOUNCY_CASTLE)
						.build(certificate);
				if (!signer.verify(verifier)) {
					return false;
				}
			}
			return true;
		} catch (Exception e) {
			throw handledException("Error checking the S/MIME signature", e);
		}
	}

	@NotNull
	static MimeBodyPart getSignedContent(@NotNull final MimePart mimePart) {
		try {
			return signed(mimePart).getContent();
		} catch (Exception e) {
			throw handledException("Error reading signed S/MIME content", e);
		}
	}

	@NotNull
	static SmimeState getStatus(@NotNull final MimePart mimePart) {
		try {
			final ContentType contentType = new ContentType(mimePart.getContentType());
			final String baseType = contentType.getBaseType();
			final String protocol = contentType.getParameter("protocol");
			if ("multipart/signed".equalsIgnoreCase(baseType)) {
				if (protocol == null) {
					return SmimeState.PROBABLY_SIGNED;
				}
				if ("application/pkcs7-signature".equalsIgnoreCase(protocol)
						|| "application/x-pkcs7-signature".equalsIgnoreCase(protocol)) {
					return SmimeState.SIGNED;
				}
			}
			if ("application/x-pkcs7-mime".equalsIgnoreCase(baseType)
					&& "signed-data".equalsIgnoreCase(contentType.getParameter("smime-type"))) {
				return SmimeState.SIGNED_ENVELOPED;
			}
			if ("application/pkcs7-mime".equalsIgnoreCase(baseType)
					|| "application/x-pkcs7-mime".equalsIgnoreCase(baseType)) {
				return SmimeState.ENCRYPTED;
			}
			return SmimeState.NEITHER;
		} catch (MessagingException e) {
			throw handledException("Error reading the S/MIME content type", e);
		}
	}

	@NotNull
	private static SMIMESigned signed(@NotNull final MimePart mimePart) throws Exception {
		if (mimePart.isMimeType("multipart/signed")) {
			return new SMIMESigned((MimeMultipart) mimePart.getContent());
		}
		if (mimePart.isMimeType("application/pkcs7-mime") || mimePart.isMimeType("application/x-pkcs7-mime")) {
			return new SMIMESigned(mimePart);
		}
		throw new MessagingException("Message is not S/MIME signed");
	}

	@NotNull
	private static JceKeyTransRecipientInfoGenerator createRecipientInfoGenerator(
			@NotNull final X509Certificate certificate,
			@NotNull final KeyEncapsulationAlgorithm keyEncapsulationAlgorithm) throws Exception {
		final JceKeyTransRecipientInfoGenerator infoGenerator;
		if (keyEncapsulationAlgorithm == KeyEncapsulationAlgorithm.RSA) {
			infoGenerator = new JceKeyTransRecipientInfoGenerator(certificate);
		} else {
			final String digestName = digestName(keyEncapsulationAlgorithm);
			final AlgorithmIdentifier oaepParams = new JcaAlgorithmParametersConverter().getAlgorithmIdentifier(
					PKCSObjectIdentifiers.id_RSAES_OAEP,
					new OAEPParameterSpec(digestName, "MGF1", new MGF1ParameterSpec(digestName), PSource.PSpecified.DEFAULT));
			infoGenerator = new JceKeyTransRecipientInfoGenerator(certificate, oaepParams);
		}
		return infoGenerator.setProvider(BOUNCY_CASTLE);
	}

	@NotNull
	private static String digestName(@NotNull final KeyEncapsulationAlgorithm algorithm)
			throws InvalidAlgorithmParameterException {
		if (algorithm == KeyEncapsulationAlgorithm.RSA_OAEP_SHA224) return "SHA-224";
		if (algorithm == KeyEncapsulationAlgorithm.RSA_OAEP_SHA256) return "SHA-256";
		if (algorithm == KeyEncapsulationAlgorithm.RSA_OAEP_SHA384) return "SHA-384";
		if (algorithm == KeyEncapsulationAlgorithm.RSA_OAEP_SHA512) return "SHA-512";
		throw new InvalidAlgorithmParameterException("Unknown S/MIME key encapsulation algorithm: " + algorithm.name());
	}

	@NotNull
	private static ASN1EncodableVector signedAttributes(@NotNull final SmimeKey smimeKey) {
		final ASN1EncodableVector attributes = new ASN1EncodableVector();
		final X509Certificate certificate = smimeKey.getCertificate();
		final BigInteger serialNumber = certificate.getSerialNumber();
		final X500Name issuerName = new X500Name(certificate.getIssuerX500Principal().getName());
		attributes.add(new SMIMEEncryptionKeyPreferenceAttribute(new IssuerAndSerialNumber(issuerName, serialNumber)));

		final SMIMECapabilityVector capabilities = new SMIMECapabilityVector();
		capabilities.addCapability(SMIMECapability.dES_EDE3_CBC);
		capabilities.addCapability(SMIMECapability.rC2_CBC, 128);
		capabilities.addCapability(SMIMECapability.dES_CBC);
		attributes.add(new SMIMECapabilitiesAttribute(capabilities));
		return attributes;
	}

	@NotNull
	private static JcaCertStore certificateStore(@NotNull final SmimeKey smimeKey) throws Exception {
		final Certificate[] certificateChain = smimeKey.getCertificateChain();
		final List<Certificate> certificates;
		if (certificateChain != null && certificateChain.length > 0) {
			certificates = Arrays.asList(certificateChain);
		} else {
			certificates = new ArrayList<>();
			certificates.add(smimeKey.getCertificate());
		}
		return new JcaCertStore(certificates);
	}

	@NotNull
	private static X509Certificate certificate(@SuppressWarnings("rawtypes") @NotNull final Store certificates,
			@NotNull final SignerInformation signer) throws Exception {
		@SuppressWarnings("unchecked")
		final X509CertificateHolder holder =
				(X509CertificateHolder) certificates.getMatches(signer.getSID()).iterator().next();
		return new JcaX509CertificateConverter()
				.setProvider(BOUNCY_CASTLE)
				.getCertificate(holder);
	}

	@NotNull
	private static MimeBodyPart canonicalize(@NotNull final MimeBodyPart mimeBodyPart)
			throws MessagingException, IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		final OutputStream output = new MimeCanonicalOutputStream(buffer);
		mimeBodyPart.writeTo(output);
		output.close();
		return new MimeBodyPart(new ByteArrayInputStream(buffer.toByteArray()));
	}

	@NotNull
	private static SmimeException handledException(@NotNull final String message, @NotNull final Exception cause) {
		return cause instanceof SmimeException
				? (SmimeException) cause
				: new SmimeException(message + ": " + cause.getMessage(), cause);
	}

	/** Converts bare CR or LF line endings to MIME's required CRLF form. */
	private static final class MimeCanonicalOutputStream extends FilterOutputStream {

		private int previousByte = -1;
		private static final byte[] CRLF = { (byte) '\r', (byte) '\n' };

		private MimeCanonicalOutputStream(@NotNull final OutputStream output) {
			super(output);
		}

		@Override
		public void write(final int value) throws IOException {
			if (value == '\r') {
				out.write(CRLF);
			} else if (value == '\n') {
				if (previousByte != '\r') {
					out.write(CRLF);
				}
			} else {
				out.write(value);
			}
			previousByte = value;
		}

		@Override
		public void write(final byte @NotNull [] bytes, final int offset, final int length) throws IOException {
			for (int index = offset; index < offset + length; index++) {
				write(bytes[index] & 0xff);
			}
		}
	}
}
