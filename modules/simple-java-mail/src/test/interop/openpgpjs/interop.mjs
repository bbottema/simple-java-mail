import * as openpgp from 'openpgp';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const fixtures = path.resolve(here, '..', '..', 'resources', 'openpgpjs');
const passphrase = 'openpgpjs-fixture-passphrase';

const crlf = value => value.replace(/\r?\n/g, '\r\n');
const bytes = value => new TextEncoder().encode(crlf(value));

function multipartEntity() {
  return bytes(
    'Content-Type: multipart/mixed; boundary="openpgpjs-inner"\n' +
    '\n' +
    '--openpgpjs-inner\n' +
    'Content-Type: text/plain; charset=UTF-8\n' +
    'Content-Transfer-Encoding: quoted-printable\n' +
    '\n' +
    'H=C3=A9llo from OpenPGP.js\n' +
    '--openpgpjs-inner\n' +
    'Content-Type: application/octet-stream; name="proof.txt"\n' +
    'Content-Transfer-Encoding: base64\n' +
    'Content-Disposition: attachment; filename="proof.txt"\n' +
    '\n' +
    'aW5kZXBlbmRlbnQgZml4dHVyZQ==\n' +
    '--openpgpjs-inner--\n');
}

function outerHeaders(subject, contentType) {
  return crlf(
    'Date: Mon, 17 Aug 2026 12:00:00 +0200\n' +
    'From: OpenPGP.js Fixture <fixture@example.com>\n' +
    'To: Receiver <receiver@example.com>\n' +
    `Message-ID: <${subject.toLowerCase().replace(/[^a-z]+/g, '-')}@openpgpjs.test>\n` +
    `Subject: ${subject}\n` +
    'MIME-Version: 1.0\n' +
    `Content-Type: ${contentType}\n\n`);
}

async function generate() {
  await mkdir(fixtures, { recursive: true });
  const generated = await openpgp.generateKey({
    type: 'rsa',
    rsaBits: 2048,
    userIDs: [{ name: 'OpenPGP.js Fixture', email: 'fixture@example.com' }],
    passphrase,
    format: 'armored'
  });
  const publicKey = await openpgp.readKey({ armoredKey: generated.publicKey });
  const privateKey = await openpgp.decryptKey({
    privateKey: await openpgp.readPrivateKey({ armoredKey: generated.privateKey }),
    passphrase
  });
  const entity = multipartEntity();
  const signature = await openpgp.sign({
    message: await openpgp.createMessage({ binary: entity }),
    signingKeys: privateKey,
    detached: true,
    format: 'armored',
    config: {
      preferredHashAlgorithm: openpgp.enums.hash.sha256,
      showVersion: false,
      showComment: false
    }
  });
  const encrypted = await openpgp.encrypt({
    message: await openpgp.createMessage({ binary: entity }),
    encryptionKeys: publicKey,
    format: 'armored',
    config: { showVersion: false, showComment: false }
  });

  const signedBoundary = 'openpgpjs-signed-boundary';
  const signed = outerHeaders('OpenPGP.js signed fixture',
      `multipart/signed; protocol="application/pgp-signature"; micalg=pgp-sha256; boundary="${signedBoundary}"`)
    + `--${signedBoundary}\r\n`
    + new TextDecoder('latin1').decode(entity)
    + `\r\n--${signedBoundary}\r\n`
    + 'Content-Type: application/pgp-signature; name="signature.asc"\r\n'
    + 'Content-Disposition: attachment; filename="signature.asc"\r\n\r\n'
    + crlf(signature).trimEnd() + '\r\n'
    + `--${signedBoundary}--\r\n`;

  const encryptedBoundary = 'openpgpjs-encrypted-boundary';
  const encryptedMime = outerHeaders('OpenPGP.js encrypted fixture',
      `multipart/encrypted; protocol="application/pgp-encrypted"; boundary="${encryptedBoundary}"`)
    + `--${encryptedBoundary}\r\n`
    + 'Content-Type: application/pgp-encrypted\r\n\r\n'
    + 'Version: 1\r\n'
    + `--${encryptedBoundary}\r\n`
    + 'Content-Type: application/octet-stream; name="encrypted.asc"\r\n'
    + 'Content-Disposition: inline; filename="encrypted.asc"\r\n\r\n'
    + crlf(encrypted).trimEnd() + '\r\n'
    + `--${encryptedBoundary}--\r\n`;

  await Promise.all([
    writeFile(path.join(fixtures, 'public-key.asc'), generated.publicKey, 'utf8'),
    writeFile(path.join(fixtures, 'private-key.asc'), generated.privateKey, 'utf8'),
    writeFile(path.join(fixtures, 'signed-mixed.eml'), signed, 'latin1'),
    writeFile(path.join(fixtures, 'encrypted-mixed.eml'), encryptedMime, 'latin1')
  ]);
  process.stdout.write('Generated OpenPGP.js fixtures.\n');
}

function splitMessage(raw) {
  const separator = raw.indexOf('\r\n\r\n');
  if (separator < 0) throw new Error('MIME message has no header separator');
  return { headers: raw.slice(0, separator), body: raw.slice(separator + 4) };
}

function boundaryFrom(headers) {
  const match = /boundary="([^"]+)"/i.exec(headers);
  if (!match) throw new Error('MIME boundary not found');
  return match[1];
}

function multipartParts(raw) {
  const message = splitMessage(raw);
  const marker = `--${boundaryFrom(message.headers)}`;
  const starts = [];
  let offset = 0;
  while ((offset = message.body.indexOf(marker, offset)) >= 0) {
    if (offset === 0 || message.body[offset - 1] === '\n') starts.push(offset);
    offset += marker.length;
  }
  const result = [];
  for (let index = 0; index + 1 < starts.length; index++) {
    const afterMarker = starts[index] + marker.length;
    if (message.body.slice(afterMarker, afterMarker + 2) === '--') break;
    const contentStart = message.body.indexOf('\n', afterMarker) + 1;
    let contentEnd = starts[index + 1];
    if (message.body.slice(contentEnd - 2, contentEnd) === '\r\n') contentEnd -= 2;
    result.push(message.body.slice(contentStart, contentEnd));
  }
  return result;
}

function partBody(part) {
  const separator = part.indexOf('\r\n\r\n');
  return separator < 0 ? part : part.slice(separator + 4);
}

async function verifySjm(signedPath, encryptedPath) {
  const publicKey = await openpgp.readKey({ armoredKey: await readFile(path.join(fixtures, 'public-key.asc'), 'utf8') });
  const privateKey = await openpgp.decryptKey({
    privateKey: await openpgp.readPrivateKey({ armoredKey: await readFile(path.join(fixtures, 'private-key.asc'), 'utf8') }),
    passphrase
  });
  const signed = await readFile(path.resolve(signedPath), 'latin1');
  const signedParts = multipartParts(signed);
  const verification = await openpgp.verify({
    message: await openpgp.createMessage({ binary: Buffer.from(signedParts[0], 'latin1') }),
    signature: await openpgp.readSignature({ armoredSignature: partBody(signedParts[1]).trim() }),
    verificationKeys: publicKey
  });
  await verification.signatures[0].verified;

  const encrypted = await readFile(path.resolve(encryptedPath), 'latin1');
  const encryptedParts = multipartParts(encrypted);
  const decrypted = await openpgp.decrypt({
    message: await openpgp.readMessage({ armoredMessage: partBody(encryptedParts[1]).trim() }),
    decryptionKeys: privateKey,
    format: 'binary'
  });
  const clear = new TextDecoder('latin1').decode(decrypted.data);
	const readableClear = clear.replace(/=\r\n/g, '').replace(/=([0-9A-F]{2})/gi,
		(_match, hex) => String.fromCharCode(Number.parseInt(hex, 16)));
  if (!readableClear.includes('Simple Java Mail outbound interoperability')) {
    throw new Error('Unexpected decrypted Simple Java Mail payload');
  }
  process.stdout.write('Verified Simple Java Mail signature and decrypted ciphertext with OpenPGP.js.\n');
}

const command = process.argv[2];
if (command === 'generate') {
  await generate();
} else if (command === 'verify-sjm' && process.argv.length === 5) {
  await verifySjm(process.argv[3], process.argv[4]);
} else {
  throw new Error('Usage: node interop.mjs generate | verify-sjm <signed.eml> <encrypted.eml>');
}
