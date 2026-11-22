#!/usr/bin/env python3
"""Inspect Git's index for private artifacts and credential patterns; never print matched values."""
import re
import subprocess
import sys
from pathlib import PurePosixPath

PRIVATE_DIRS = {".signing", ".toolchain", "build", "dist", "__pycache__"}
PRIVATE_SUFFIXES = {".p12", ".pfx", ".jks", ".keystore", ".pem", ".key", ".db", ".sqlite", ".sqlite3", ".apk", ".aab", ".idsig"}
PATTERNS = {
    "private-key block": re.compile(rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "GitHub credential": re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})\b"),
    "possible personal API token": re.compile(rb"(?<![A-Za-z0-9])[A-Fa-f0-9]{40}(?![A-Za-z0-9])"),
    "credential literal": re.compile(rb'''(?i)(?:api[_-]?key|access[_-]?token|client[_-]?secret|signing_password|password)\s*[:=]\s*["'][A-Za-z0-9/_+=.\-]{16,}["']'''),
    "personal home path": re.compile(rb"/Users/[A-Za-z0-9_.-]+/"),
}

def main():
    paths = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
    issues = []
    checked = 0
    for name in filter(None, paths):
        path = PurePosixPath(name)
        if (PRIVATE_DIRS.intersection(path.parts) or path.suffix.lower() in PRIVATE_SUFFIXES
                or path.name == ".env" or (path.name.startswith(".env.") and path.name != ".env.example")
                or "-private." in path.name or "-private-" in path.name):
            issues.append(f"{name}: private/generated artifact")
            continue
        data = subprocess.check_output(["git", "show", f":{name}"])
        checked += 1
        try:
            data.decode("utf-8")
        except UnicodeDecodeError:
            continue  # Vendored third-party JARs are covered by the dependency manifest.
        for label, pattern in PATTERNS.items():
            match = pattern.search(data)
            if match:
                line = data[:match.start()].count(b"\n") + 1
                issues.append(f"{name}:{line}: {label} (value hidden)")
    if issues:
        print("Privacy check failed:\n" + "\n".join(issues))
        return 1
    print(f"Privacy check passed: {checked} indexed files; no private artifacts or matched credential patterns.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
