package org.simplejavamail.internal.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class CertificationUtil {
	public static X509Certificate readFromPem(File pemFile)
			throws CertificateException, NoSuchProviderException, FileNotFoundException {
		return readFromPem(new FileInputStream(pemFile));
	}

	public static X509Certificate readFromPem(InputStream pemData)
			throws CertificateException, NoSuchProviderException {
		return (X509Certificate) CertificateFactory.getInstance("X.509", "BC")
				.generateCertificate(pemData);
	}
}
