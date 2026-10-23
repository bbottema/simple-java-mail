"""Build/check a deterministic source download; no generated copy is edited by hand."""
import argparse
import io
from pathlib import Path
import zipfile


def canonical_bytes(file):
    # Git may check these text sources out as CRLF on Windows. Downloads must not drift solely because of the build host.
    return file.read_bytes().replace(b"\r\n", b"\n")


def is_source_path(relative):
    if any((part.startswith(".") and part != ".gitignore") or part in ("target", "__pycache__") for part in relative.parts):
        return False
    # Someone may run Terraform directly beside the templates. Never turn its generated state/inputs or local keys into a download.
    name = relative.name.lower()
    excluded = (".log", ".p12", ".pfx", ".jks", ".pem", ".key", ".crt", ".tfstate", ".tfplan", ".tfvars", ".tfvars.json")
    return not (name.endswith(excluded) or ".tfstate." in name)


def archive(source):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as bundle:
        license_entry = zipfile.ZipInfo("LICENSE", (2026, 10, 16, 0, 0, 0))
        license_entry.compress_type = zipfile.ZIP_DEFLATED
        bundle.writestr(license_entry, canonical_bytes(source.parents[1] / "LICENSE"))
        for file in sorted(source.rglob("*")):
            if not file.is_file() or not is_source_path(file.relative_to(source)):
                continue
            entry = zipfile.ZipInfo("tools/integration-patterns/" + file.relative_to(source).as_posix(), (2026, 10, 16, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(entry, canonical_bytes(file))
        # Runnable case studies compile the original helpers unchanged. Include their canonical paths, not hand-maintained forks.
        for study in ("relaydesk", "polar-meridian"):
            helpers = source.parents[1] / "simplejavamail.org/src/assets/journal/articles" / study / "examples"
            for file in sorted(helpers.iterdir()):
                if file.is_file() and (file.suffix in (".java", ".sql") or file.name == "README.md"):
                    entry = zipfile.ZipInfo(file.relative_to(source.parents[1]).as_posix(), (2026, 10, 16, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    bundle.writestr(entry, canonical_bytes(file))
    return output.getvalue()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    destination = source.parents[1] / "simplejavamail.org/src/assets/guides/integration-patterns/strategic-patterns.zip"
    expected = archive(source)
    if args.check:
        if not destination.exists() or destination.read_bytes() != expected:
            raise SystemExit("Integration-pattern download is stale; run package_examples.py")
        print("Integration-pattern download matches canonical source")
    else:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(expected)
        print(f"Wrote {destination.name}: {len(expected)} bytes")
