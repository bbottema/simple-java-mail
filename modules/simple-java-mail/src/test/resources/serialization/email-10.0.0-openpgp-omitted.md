# Earlier unreleased OpenPGP snapshot

`email-10.0.0-openpgp-omitted.base64` was generated on real JDK 11 before decision 6b was implemented, using the
current working-tree classes at HEAD `69c483280394a743b5b73312ed0fcfc3dfd972d5` plus the preceding API work.
Both OpenPGP configs were supplied to the builder, but the old `Email` fields were transient. The snapshot contains
neither config. Its SHA-256 is `a9c8c499e4848b32ec8314d9eda65bb464a00276a7145eab23d580ed3ad77d84`.

This fixture establishes backward reading and missing-value behavior; it is not a released 9.x format or a migration
promise to recover keys that were never stored. The dummy key bytes and `fixture-only` passphrase are not real keys.
The existing 9.1.7 fixture separately verifies the supported older released Email format.
