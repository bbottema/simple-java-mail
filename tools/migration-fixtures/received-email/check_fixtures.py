#!/usr/bin/env python3
"""Compile the bounded fixture contribution against explicit, separate artifact sets.

This does not execute OpenRewrite or produce migration findings. Expected report rows
are declarative inputs to the separately owned #766 tooling implementation.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


FIXTURE_ROOT = Path(__file__).resolve().parent


def artifact_set(classpath):
    paths = [Path(value).resolve(strict=True) for value in classpath.split(os.pathsep) if value]
    if not paths or any(not path.is_file() or path.suffix.lower() != ".jar" for path in paths):
        raise ValueError("Supply a nonempty explicit JAR classpath; directories/moving classes are not frozen artifacts")
    if len(paths) != len(set(paths)):
        raise ValueError("The classpath contains a duplicate artifact")
    return paths


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as content:
        for chunk in iter(lambda: content.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def identities(paths):
    return [{"path": str(path), "sha256": file_hash(path), "size": path.stat().st_size} for path in paths]


def source_hashes():
    return {str(path.relative_to(FIXTURE_ROOT)): file_hash(path)
            for family in ("released", "manual", "negative")
            for path in sorted((FIXTURE_ROOT / family).glob("*.java"))}


def java_tool(jdk_home, name):
    if jdk_home:
        suffix = ".exe" if os.name == "nt" else ""
        return str((Path(jdk_home) / "bin" / (name + suffix)).resolve(strict=True))
    return name


def run(command, log_path):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", check=False)
    log_path.write_text(result.stdout + result.stderr, encoding="utf-8")
    return result


def compile_sources(javac, classpath, sources, evidence, name, expected_success=True):
    classes = evidence / (name + "-classes")
    classes.mkdir(parents=True, exist_ok=True)
    result = run([javac, "--release", "11", "-encoding", "UTF-8", "-classpath", classpath,
                  "-d", str(classes), *map(str, sources)], evidence / (name + ".log"))
    if (result.returncode == 0) != expected_success:
        expectation = "succeed" if expected_success else "fail on removed legacy API"
        raise RuntimeError(f"{name} should {expectation}; see {evidence / (name + '.log')}")


def inspect_types(javap, classpath, owners, evidence, prefix):
    inspected = {}
    for owner in sorted(owners):
        result = run([javap, "-public", "-s", "-classpath", classpath, owner],
                     evidence / (prefix + "-" + owner + ".txt"))
        if result.returncode != 0:
            inspected[owner] = None
            continue
        declaration = re.search(r"(?m)^.*\b(?:class|interface|enum)\s+" + re.escape(owner) + r"(?:\s|<|$)", result.stdout)
        if declaration is None or not declaration.group(0).startswith("public "):
            # Serialization-only package-private shells do not restore a supported application API.
            inspected[owner] = None
            continue
        members = set()
        declaration = ""
        for line in result.stdout.splitlines():
            stripped = line.strip()
            if stripped.startswith("descriptor:"):
                match = re.search(r"([\w$]+)\([^)]*\)", declaration)
                if match:
                    members.add((match.group(1), stripped[len("descriptor:"):].strip()))
            elif stripped:
                declaration = stripped
        inspected[owner] = members
    return inspected


def require_symbol(inspected, symbol, present, label):
    members = inspected.get(symbol["owner"])
    pair = (symbol["method"], symbol["descriptor"])
    found = members is not None and pair in members
    if found != present:
        expected = "present" if present else "absent"
        raise AssertionError(f"{label}: {symbol['owner']}#{pair[0]} {pair[1]} must be {expected}")


def validate_expectations(manifest):
    expected = json.loads((FIXTURE_ROOT / "expected-findings.json").read_text(encoding="utf-8"))
    if expected["defaultSourceDisposition"] != "UNCHANGED":
        raise AssertionError("The packet must remain source-preserving")
    permitted = {"SJM10-R028", "SJM10-R033", "SJM10-R040"}
    identifiers = set()
    for finding in expected["findings"]:
        if finding["rule"] not in permitted or finding["disposition"] != "MANUAL_REVIEW":
            raise AssertionError("Semantic removals require the existing manual report responsibilities")
        if finding["caseId"] in identifiers:
            raise AssertionError("Duplicate case ID")
        identifiers.add(finding["caseId"])
        (FIXTURE_ROOT / finding["source"]).resolve(strict=True)
    for control in expected["negativeControls"]:
        if control["expectedFindings"]:
            raise AssertionError("A negative control cannot claim an SJM finding")
    if manifest["releasedVersion"] != "9.3.10":
        raise AssertionError("This contribution's source version is published 9.3.10")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--released-classpath", required=True)
    parser.add_argument("--target-classpath")
    parser.add_argument("--released-only", action="store_true", help="Only establish the released witness; not final target acceptance")
    parser.add_argument("--evidence-dir", required=True)
    parser.add_argument("--jdk-home")
    args = parser.parse_args()
    if args.released_only == bool(args.target_classpath):
        parser.error("Supply either --target-classpath or --released-only")
    evidence = Path(args.evidence_dir).resolve()
    if evidence == FIXTURE_ROOT or FIXTURE_ROOT in evidence.parents:
        parser.error("Keep execution evidence outside the committed fixture corpus")
    evidence.mkdir(parents=True, exist_ok=True)
    released_paths = artifact_set(args.released_classpath)
    released_classpath = os.pathsep.join(map(str, released_paths))
    javac = java_tool(args.jdk_home, "javac")
    javap = java_tool(args.jdk_home, "javap")
    manifest = json.loads((FIXTURE_ROOT / "symbols.json").read_text(encoding="utf-8"))
    validate_expectations(manifest)
    original_hashes = source_hashes()
    released_sources = sorted((FIXTURE_ROOT / "released").glob("*.java"))
    compile_sources(javac, released_classpath, released_sources, evidence, "released-input")
    released_symbols = inspect_types(javap, released_classpath,
                                     {symbol["owner"] for symbol in manifest["releasedSymbols"]}, evidence, "released")
    for symbol in manifest["releasedSymbols"]:
        require_symbol(released_symbols, symbol, True, "released descriptor")
    result = {"releasedArtifacts": identities(released_paths), "targetChecked": False,
              "sourceFilesUnchanged": False, "recipeExecuted": False}
    if args.target_classpath:
        target_paths = artifact_set(args.target_classpath)
        if set(released_paths) & set(target_paths):
            # Foundational mail/activation dependencies can be shared; incompatible core versions cannot.
            shared_core = [path for path in set(released_paths) & set(target_paths) if "core-module" in path.name]
            if shared_core:
                raise ValueError("The target cannot reuse the released core artifact")
        target_classpath = os.pathsep.join(map(str, target_paths))
        target_owners = {symbol["owner"] for symbol in manifest["releasedSymbols"] + manifest["targetSymbols"]}
        target_owners.update(manifest["removedTargetTypes"])
        target_owners.update(manifest["targetTypes"])
        inspected = inspect_types(javap, target_classpath, target_owners, evidence, "target")
        for owner in manifest["targetTypes"]:
            if inspected[owner] is None:
                raise AssertionError(f"Required target type is unavailable: {owner}")
        for symbol in manifest["releasedSymbols"]:
            require_symbol(inspected, symbol, symbol["target"] == "retained", "target disposition")
        for symbol in manifest["targetSymbols"]:
            require_symbol(inspected, symbol, True, "target descriptor")
        for owner in manifest["removedTargetTypes"]:
            if inspected.get(owner) is not None:
                raise AssertionError(f"Superseded public type remains: {owner}")
        for symbol in manifest["removedUnreleasedTargetMethods"]:
            if any(name == symbol["method"] for name, _ in inspected[symbol["owner"]]):
                raise AssertionError(f"Superseded target-only method remains: {symbol}")
        frozen_descriptors = {owner: [{"method": name, "descriptor": descriptor} for name, descriptor in sorted(members)]
                              for owner, members in inspected.items() if members is not None}
        (evidence / "target-api-descriptors.json").write_text(json.dumps(frozen_descriptors, indent=2) + "\n", encoding="utf-8")
        continuing = [FIXTURE_ROOT / "released" / "NativeBodies.java"]
        continuing.extend(sorted((FIXTURE_ROOT / "manual").glob("*.java")))
        continuing.extend(sorted((FIXTURE_ROOT / "negative").glob("*.java")))
        compile_sources(javac, target_classpath, continuing, evidence, "target-manual-and-controls")
        compile_sources(javac, target_classpath, [FIXTURE_ROOT / "released" / "LegacyReceivedEmail.java"],
                        evidence, "target-removed-legacy", expected_success=False)
        result.update(targetArtifacts=identities(target_paths), targetChecked=True)
    if original_hashes != source_hashes():
        raise AssertionError("Compilation changed a fixture source")
    result["sourceFilesUnchanged"] = True
    (evidence / "fixture-result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Released witness and requested artifact checks completed; no migration recipe was executed.")


if __name__ == "__main__":
    main()
