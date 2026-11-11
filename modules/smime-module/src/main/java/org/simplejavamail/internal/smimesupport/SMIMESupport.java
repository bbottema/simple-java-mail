package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataHandler;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.bouncycastle.mail.smime.SMIMEEnvelopedGenerator;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.jcajce.JcaAlgorithmParametersConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.internal.protection.ProtectionLayerResult;
import org.simplejavamail.internal.util.MessageIdFixingMimeMessage;
import org.simplejavamail.utils.mail.smime.KeyEncapsulationAlgorithm;
import org.simplejavamail.utils.mail.smime.SmimeKey;

import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;

import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_ENCRYPTING_SMIME_FOR_RECIPIENTS;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_OBTAINING_SMIME_KEY;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;

/** Optional S/MIME entry point; private-key reuse belongs to the factory runtime, while reads produce individual layer facts. */
@SuppressWarnings("unused")
public class SMIMESupport implements SMIMEModule {
    @Nullable private final SmimeKeyCache keyCache;

    public SMIMESupport() { keyCache = null; }

    SMIMESupport(@NotNull final SmimeKeyCache keyCache) { this.keyCache = keyCache; }

    @Override
    public SMIMEModule createRuntime(final int capacity) {
        return new SMIMESupport(new SmimeKeyCache(capacity));
    }

    @Override
    @NotNull
    public ProtectionLayerResult openIncomingLayer(@NotNull final Session session,
            @NotNull final MimeMessage originalMessage, @Nullable final Pkcs12Config pkcs12Config) {
        return new SmimeIncomingLayer(this::retrieveSmimeKeyFromPkcs12Keystore).open(session, originalMessage, pkcs12Config);
    }

	@NotNull
	@Override
	public MimeMessage signMessageWithSmime(@Nullable final Session session, @NotNull final Email email, @NotNull final MimeMessage messageToProtect, @NotNull final SmimeSigningConfig smimeSigningConfig) {
		try {
			final MimeBodyPart signedBodyPart = SmimeCryptoSupport.sign(extractMimeBodyPart(messageToProtect),
					retrieveSmimeKeyFromPkcs12Keystore(smimeSigningConfig.getPkcs12Config()),
					defaultTo(smimeSigningConfig.getSignatureAlgorithm(), SmimeCryptoSupport.DEFAULT_SIGNATURE_ALGORITHM_NAME));
			return createProtectedMessage(session, email, messageToProtect, signedBodyPart);
		} catch (MessagingException | IOException e) {
			throw new SmimeException("Error signing message with S/MIME", e);
		}
	}

	@NotNull
	@Override
	public MimeMessage encryptMessageWithSmime(@Nullable final Session session, @NotNull final Email email, @NotNull final MimeMessage messageToProtect, @NotNull final SmimeEncryptionConfig smimeEncryptionConfig) {
		try {
			final MimeBodyPart encryptedBodyPart = SmimeCryptoSupport.encrypt(extractMimeBodyPart(messageToProtect),
					smimeEncryptionConfig.getX509Certificate(),
					ofNullable(smimeEncryptionConfig.getKeyEncapsulationAlgorithm())
							.map(KeyEncapsulationAlgorithm::valueOf)
							.orElse(SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM),
					ofNullable(smimeEncryptionConfig.getCipherAlgorithm())
							.map(CMSAlgorithmResolver::resolve)
							.orElse(SmimeCryptoSupport.DEFAULT_CIPHER));
			return createProtectedMessage(session, email, messageToProtect, encryptedBodyPart);
		} catch (MessagingException | IOException e) {
			throw new SmimeException("Error encrypting message with S/MIME", e);
		}
	}

	@NotNull
	@Override
	public MimeMessage encryptMessageWithSmimeForRecipients(@Nullable final Session session, @NotNull final Email email,
			@NotNull final MimeMessage messageToProtect, @NotNull final Collection<X509Certificate> recipientCerts,
			@Nullable final String keyEncapsulationAlgorithmStr, @Nullable final String cipherAlgorithmStr) {
		try {
			final KeyEncapsulationAlgorithm keyEncapsulationAlgorithm = ofNullable(keyEncapsulationAlgorithmStr)
					.map(KeyEncapsulationAlgorithm::valueOf)
					.orElse(SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM);
			final ASN1ObjectIdentifier cmsAlgorithm = ofNullable(cipherAlgorithmStr)
					.map(CMSAlgorithmResolver::resolve)
					.orElse(SmimeCryptoSupport.DEFAULT_CIPHER);

			final SMIMEEnvelopedGenerator generator = new SMIMEEnvelopedGenerator();
			for (X509Certificate cert : recipientCerts) {
				generator.addRecipientInfoGenerator(createRecipientInfoGenerator(cert, keyEncapsulationAlgorithm));
			}

			final OutputEncryptor encryptor = new JceCMSContentEncryptorBuilder(cmsAlgorithm)
					.setProvider(SmimeCryptoSupport.BOUNCY_CASTLE).build();

			final MimeBodyPart encryptedBodyPart = generator.generate(extractMimeBodyPart(messageToProtect), encryptor);
			return createProtectedMessage(session, email, messageToProtect, encryptedBodyPart);
		} catch (Exception e) {
			throw new SmimeException(ERROR_ENCRYPTING_SMIME_FOR_RECIPIENTS, e);
		}
	}

	private static JceKeyTransRecipientInfoGenerator createRecipientInfoGenerator(@NotNull final X509Certificate certificate,
			@NotNull final KeyEncapsulationAlgorithm keyEncapsulationAlgorithm)
			throws CertificateEncodingException, InvalidAlgorithmParameterException {
		final JceKeyTransRecipientInfoGenerator infoGenerator;
		if (keyEncapsulationAlgorithm == KeyEncapsulationAlgorithm.RSA) {
			infoGenerator = new JceKeyTransRecipientInfoGenerator(certificate);
		} else {
			final String digestName = determineDigestNameForOaep(keyEncapsulationAlgorithm);
			final AlgorithmIdentifier oaepParams = new JcaAlgorithmParametersConverter().getAlgorithmIdentifier(
					PKCSObjectIdentifiers.id_RSAES_OAEP,
					new OAEPParameterSpec(digestName, "MGF1", new MGF1ParameterSpec(digestName), PSource.PSpecified.DEFAULT));
			infoGenerator = new JceKeyTransRecipientInfoGenerator(certificate, oaepParams);
		}
		infoGenerator.setProvider(SmimeCryptoSupport.BOUNCY_CASTLE);
		return infoGenerator;
	}

	@NotNull
	private static String determineDigestNameForOaep(@NotNull final KeyEncapsulationAlgorithm alg) throws InvalidAlgorithmParameterException {
		if (alg == KeyEncapsulationAlgorithm.RSA_OAEP_SHA224) return "SHA-224";
		if (alg == KeyEncapsulationAlgorithm.RSA_OAEP_SHA256) return "SHA-256";
		if (alg == KeyEncapsulationAlgorithm.RSA_OAEP_SHA384) return "SHA-384";
		if (alg == KeyEncapsulationAlgorithm.RSA_OAEP_SHA512) return "SHA-512";
		throw new InvalidAlgorithmParameterException("Unknown S/MIME key encapsulation algorithm: " + alg.name());
	}

	@NotNull
	private static MimeBodyPart extractMimeBodyPart(@NotNull final MimeMessage message)
			throws MessagingException {
		final UpdatableMimeBodyPart bodyPart = new UpdatableMimeBodyPart();
		bodyPart.setDataHandler(new IsolatedContentDataHandler(message.getDataHandler()));
		final Enumeration<Header> headers = message.getAllHeaders();
		while (headers.hasMoreElements()) {
			final Header header = headers.nextElement();
			if (header.getName().regionMatches(true, 0, "Content-", 0, "Content-".length())) {
				bodyPart.addHeader(header.getName(), header.getValue());
			}
		}
		bodyPart.updateHeadersPublic();
		return bodyPart;
	}

	@NotNull
	private static MimeMessage createProtectedMessage(@Nullable final Session session,
			@NotNull final Email email,
			@NotNull final MimeMessage original,
			@NotNull final MimeBodyPart protectedBodyPart)
			throws MessagingException, IOException {
		final Session effectiveSession = session != null ? session : original.getSession();
		if (effectiveSession == null) {
			throw new MessagingException("A Session is required to create an S/MIME message");
		}
		final MimeMessage protectedMessage = new MessageIdFixingMimeMessage(effectiveSession, email.getId());
		copyAllHeaderLines(original, protectedMessage);
		protectedMessage.setContent(protectedBodyPart.getContent(), protectedBodyPart.getContentType());
		copyAllHeaders(protectedBodyPart, protectedMessage);
		return protectedMessage;
	}

	private static void copyAllHeaderLines(@NotNull final MimeMessage from, @NotNull final MimeMessage to)
			throws MessagingException {
		final Enumeration<String> headerLines = from.getAllHeaderLines();
		while (headerLines.hasMoreElements()) {
			to.addHeaderLine(headerLines.nextElement());
		}
	}

	private static void copyAllHeaders(@NotNull final MimeBodyPart from, @NotNull final MimeMessage to) throws MessagingException {
		final Enumeration<Header> headers = from.getAllHeaders();
		while (headers.hasMoreElements()) {
			final Header h = headers.nextElement();
			to.setHeader(h.getName(), h.getValue());
		}
	}

	private static final class UpdatableMimeBodyPart extends MimeBodyPart {
		private void updateHeadersPublic() throws MessagingException {
			super.updateHeaders();
		}
	}

	/** Isolates Bouncy Castle's command-map changes while retaining the source codec and synchronous I/O failures. */
	private static final class IsolatedContentDataHandler extends DataHandler {
		private final DataHandler source;

		private IsolatedContentDataHandler(final DataHandler source) {
			super(source.getDataSource());
			this.source = source;
		}

		@Override
		public Object getContent() throws IOException {
			return source.getContent();
		}

		@Override
		public void writeTo(final OutputStream output) throws IOException {
			source.writeTo(output);
		}
	}

	@NotNull
	private SmimeKey retrieveSmimeKeyFromPkcs12Keystore(@NotNull Pkcs12Config pkcs12Config) {
		if (keyCache == null) {
            throw new IllegalStateException("Private key loading requires a factory-owned S/MIME runtime.");
        }
		return keyCache.get(pkcs12Config, configuration -> {
			final SmimeKey smimeKey = produceSmimeKey(configuration);
			if (smimeKey == null) {
				throw new SmimeException(ERROR_OBTAINING_SMIME_KEY);
			}
			return smimeKey;
		});
	}

	@Nullable
	private SmimeKey produceSmimeKey(final @NotNull Pkcs12Config pkcs12) {
		final char[] storePassword = pkcs12.getStorePassword();
		final char[] keyPassword = pkcs12.getKeyPassword();
		try {
			final KeyStore keyStore = KeyStore.getInstance("PKCS12", SmimeCryptoSupport.BOUNCY_CASTLE);
			keyStore.load(new ByteArrayInputStream(pkcs12.getPkcs12StoreData()), storePassword);
			if (!keyStore.isKeyEntry(pkcs12.getKeyAlias())) {
				return null;
			}
			final PrivateKey privateKey = (PrivateKey) keyStore.getKey(pkcs12.getKeyAlias(), keyPassword);
			final Certificate[] certificateChain = keyStore.getCertificateChain(pkcs12.getKeyAlias());
			final X509Certificate[] x509CertificateChain = new X509Certificate[certificateChain.length];
			for (int index = 0; index < certificateChain.length; index++) {
				x509CertificateChain[index] = (X509Certificate) certificateChain[index];
			}
			return new SmimeKey(privateKey, x509CertificateChain);
		} catch (Exception e) {
			throw new SmimeException("Could not load the supplied PKCS12 S/MIME key", e);
		} finally {
			Arrays.fill(storePassword, '\0');
			Arrays.fill(keyPassword, '\0');
		}
	}

}
