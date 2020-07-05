package org.simplejavamail.internal.util;

import org.simplejavamail.MailException;

import org.jetbrains.annotations.NotNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static java.lang.Class.forName;
import static java.lang.String.format;
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
				Security.addProvider(getProvider(bouncyCastleClass));
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(format("Class found but also not found??? (%s)", BOUNCY_CASTLE_PROVIDER_CLASS));
			}
		} else {
			throw new SmimeSupportMissingException();
		}

		return (X509Certificate) CertificateFactory.getInstance("X.509", "BC")
				.generateCertificate(pemData);
	}

	@NotNull
	// copied from com.github.bbottema:java-reflection
	private static Provider getProvider(final Class<Provider> bouncyCastleClass) {
		try {
			return bouncyCastleClass.getConstructor().newInstance();
		} catch (SecurityException e) {
			throw new RuntimeException("unable to invoke parameterless constructor; security problem", e);
		} catch (InstantiationException e) {
			throw new RuntimeException("unable to complete instantiation of object", e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException("unable to access parameterless constructor", e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException("unable to invoke parameterless constructor", e);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException("unable to find parameterless constructor (not public?)", e);
		}
	}

	private static class SmimeSupportMissingException extends MailException {
		SmimeSupportMissingException() {
			super("Can't read x509 certificate from PEM file (missing BouncyCastle Provider). "
					+ "Is the S/MIME module on the class path?");
		}
	}
}
