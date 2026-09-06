package org.simplejavamail.internal.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class CertificationUtil {

	public static X509Certificate readFromPem(File pemFile)
			throws CertificateException, NoSuchProviderException, FileNotFoundException {
		try (InputStream pemData = new FileInputStream(pemFile)) {
			return readFromPem(pemData);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new CertificateException("Unable to close PEM certificate file " + pemFile, e);
		}
	}

	public static X509Certificate readFromPem(InputStream pemData)
			throws CertificateException, NoSuchProviderException {
		return (X509Certificate) CertificateFactory.getInstance("X.509")
				.generateCertificate(pemData);
	}
}
