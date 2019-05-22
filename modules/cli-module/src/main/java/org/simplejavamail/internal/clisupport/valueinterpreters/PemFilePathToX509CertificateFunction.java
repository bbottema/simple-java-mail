package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.simplejavamail.internal.util.CertificationUtil;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileNotFoundException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class PemFilePathToX509CertificateFunction extends FileBasedFunction<X509Certificate> {
	
	@Override
	public Class<String> getFromType() {
		return String.class;
	}
	
	@Override
	public Class<X509Certificate> getTargetType() {
		return X509Certificate.class;
	}
	
	@Nonnull
	@Override
	protected X509Certificate convertFile(File msgFile) {
		try {
			return CertificationUtil.readFromPem(msgFile);
		} catch (CertificateException | NoSuchProviderException | FileNotFoundException e) {
			throw new IncompatibleTypeException(msgFile, String.class, X509Certificate.class, e);
		}
	}
}