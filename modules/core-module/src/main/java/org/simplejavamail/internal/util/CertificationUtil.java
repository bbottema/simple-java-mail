package org.simplejavamail.internal.util;

import org.simplejavamail.MailException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static java.lang.Class.forName;
import static java.lang.String.format;
import static org.bbottema.javareflection.ClassUtils.newInstanceSimple;
import static org.simplejavamail.internal.util.MiscUtil.classAvailable;

public class CertificationUtil {

	private static final String BOUNCY_CASTLE_PROVIDER_CLASS = "org.bouncycastle.jce.provider.BouncyCastleProvider";

	public static X509Certificate readFromPem(File pemFile)
			throws CertificateException, NoSuchProviderException, FileNotFoundException {
		return readFromPem(new FileInputStream(pemFile));
	}

	@SuppressWarnings("unchecked")
	public static X509Certificate readFromPem(InputStream pemData)
			throws CertificateException, NoSuchProviderException {
		if (classAvailable(BOUNCY_CASTLE_PROVIDER_CLASS)) {
			try {
				Class<Provider> bouncyCastleClass = (Class<Provider>) forName(BOUNCY_CASTLE_PROVIDER_CLASS);
				Security.addProvider(newInstanceSimple(bouncyCastleClass));
			} catch (ClassNotFoundException e) {
				throw new AssertionError(format("Class found but also not found??? (%s)", BOUNCY_CASTLE_PROVIDER_CLASS));
			}
		} else {
			throw new SmimeSupportMissingException();
		}

		return (X509Certificate) CertificateFactory.getInstance("X.509", "BC")
				.generateCertificate(pemData);
	}

	private static class SmimeSupportMissingException extends MailException {
		SmimeSupportMissingException() {
			super("Can't read x509 certificate from PEM file (missing BouncyCastle Provider). "
					+ "Is the S/MIME module on the class path?");
		}
	}
}
