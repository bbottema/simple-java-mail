# OpenPGP.js interoperability fixture

The checked-in keys and MIME messages under `src/test/resources/openpgpjs` were generated with OpenPGP.js 6.3.1, independently of the Bouncy Castle implementation used by `openpgp-module`.

From this directory:

```text
npm install
node interop.mjs generate
node interop.mjs verify-sjm ../../../../target/openpgpjs-interop/sjm-signed.eml ../../../../target/openpgpjs-interop/sjm-encrypted.eml
```

The first command refreshes the independent inbound fixtures. The JUnit interoperability test writes the two Simple Java Mail outbound samples under `target/openpgpjs-interop`; the final command verifies and decrypts those samples with OpenPGP.js.

The fixture passphrase is `openpgpjs-fixture-passphrase`. These are test-only keys and must never be used outside this fixture.
