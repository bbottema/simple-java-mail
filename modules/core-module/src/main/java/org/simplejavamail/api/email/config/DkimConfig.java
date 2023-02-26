package org.simplejavamail.api.email.config;

import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;

/**
 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
 */
@ToString(exclude = "dkimPrivateKeyData")
@Getter
public class DkimConfig implements Serializable {

	private static final long serialVersionUID = 1234567L;

	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
	 */
	private final byte[] dkimPrivateKeyData;

	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
	 */
	private final String dkimSigningDomain;

	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
	 */
	private final String dkimSelector;

	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
	 */
	@Nullable private final Set<String> excludedHeadersFromDkimDefaultSigningList;

	DkimConfig(byte[] dkimPrivateKeyData, String dkimSigningDomain, String dkimSelector, @Nullable Set<String> excludedHeadersFromDkimDefaultSigningList) {
		this.dkimPrivateKeyData = dkimPrivateKeyData.clone();
		this.dkimSigningDomain = dkimSigningDomain;
		this.dkimSelector = dkimSelector;
		this.excludedHeadersFromDkimDefaultSigningList = excludedHeadersFromDkimDefaultSigningList;
	}

	/**
	 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
	 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
	 */
	public byte[] getDkimPrivateKeyData() {
		return dkimPrivateKeyData.clone();
	}

	public static DkimConfigBuilder builder() {
		return new DkimConfigBuilder();
	}

	@ToString
	public static class DkimConfigBuilder {
		private byte[] dkimPrivateKeyData;
		private String dkimSigningDomain;
		private String dkimSelector;
		private @Nullable Set<String> excludedHeadersFromDkimDefaultSigningList;

		DkimConfigBuilder() {
		}

		/**
		 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
		 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
		 */
		public DkimConfigBuilder dkimPrivateKeyPath(String dkimPrivateKeyFile) {
			return dkimPrivateKeyPath(new File(dkimPrivateKeyFile));
		}

		/**
		 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
		 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
		 */
		public DkimConfigBuilder dkimPrivateKeyPath(File dkimPrivateKeyFile) {
			try (FileInputStream dkimPrivateKeyInputStream = new FileInputStream(dkimPrivateKeyFile)) {
				dkimPrivateKeyData(dkimPrivateKeyInputStream);
			} catch (IOException e) {
				throw new IllegalStateException(format("error reading DKIM private key file[%s]", dkimPrivateKeyFile), e);
			}
			return this;
		}

		/**
		 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
		 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
		 */
		public DkimConfigBuilder dkimPrivateKeyData(InputStream dkimPrivateKeyInputStream) {
			try {
				dkimPrivateKeyData(readInputStreamToBytes(dkimPrivateKeyInputStream));
			} catch (IOException e) {
				throw new IllegalStateException("error reading DKIM private key inputstream", e);
			}
			return this;
		}

		/**
		 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
		 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
		 */
		public DkimConfigBuilder dkimPrivateKeyData(String dkimPrivateKeyData) {
			dkimPrivateKeyData(dkimPrivateKeyData.getBytes(UTF_8));
			return this;
		}

		/**
		 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
		 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
		 */
		public DkimConfigBuilder dkimPrivateKeyData(byte[] dkimPrivateKeyData) {
			this.dkimPrivateKeyData = dkimPrivateKeyData.clone();
			return this;
		}

		/**
		 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
		 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
		 */
		public DkimConfigBuilder dkimSigningDomain(String dkimSigningDomain) {
			this.dkimSigningDomain = dkimSigningDomain;
			return this;
		}

		/**
		 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
		 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
		 */
		public DkimConfigBuilder dkimSelector(String dkimSelector) {
			this.dkimSelector = dkimSelector;
			return this;
		}

		/**
		 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
		 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
		 */
		public DkimConfigBuilder excludedHeadersFromDkimDefaultSigningList(@Nullable Set<String> excludedHeadersFromDkimDefaultSigningList) {
			this.excludedHeadersFromDkimDefaultSigningList = excludedHeadersFromDkimDefaultSigningList;
			return this;
		}

		/**
		 * @see EmailPopulatingBuilder#signWithDomainKey(DkimConfig)
		 * @see EmailPopulatingBuilder#signWithDomainKey(byte[], String, String, Set)
		 */
		public DkimConfigBuilder excludedHeadersFromDkimDefaultSigningList(@Nullable String... excludedHeadersFromDkimDefaultSigningList) {
			this.excludedHeadersFromDkimDefaultSigningList = new HashSet<>(asList(excludedHeadersFromDkimDefaultSigningList));
			return this;
		}

		public DkimConfig build() {
			return new DkimConfig(dkimPrivateKeyData, dkimSigningDomain, dkimSelector, excludedHeadersFromDkimDefaultSigningList);
		}
	}
}