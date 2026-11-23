#!/usr/bin/env python3
"""Build a signed APK using the official Android SDK, without Gradle downloads."""
import argparse
import hashlib
import os
import secrets
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def run(args, **kwargs):
    print("+", " ".join(str(a) for a in args), flush=True)
    subprocess.run([str(a) for a in args], check=True, **kwargs)

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--test-apk", action="store_true")
    p.add_argument("--without-onyx-sdk", action="store_true", help="Diagnostic app with no BOOX SDK or transitive dependencies")
    args = p.parse_args()
    if args.test_apk and args.without_onyx_sdk:
        raise SystemExit("The SDK-free diagnostic is an app build, not an instrumentation APK.")
    version="0.21.1-sdkfree" if args.without_onyx_sdk else "0.21.1"
    version_code="67" if args.without_onyx_sdk else "68"
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    build_tools = Path(os.environ.get("ANDROID_BUILD_TOOLS", str(sdk / "build-tools/35.0.0")))
    android = Path(os.environ.get("ANDROID_JAR", str(ROOT / ".toolchain/android-35/android.jar")))
    if not android.exists():
        android = sdk / "platforms/android-35/android.jar"
    if not android.exists():
        raise SystemExit("Android 35 android.jar missing. See README.md build instructions.")
    javac = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-11.0.2.jdk/Contents/Home")) / "bin/javac"
    if not javac.exists():
        javac = Path(shutil.which("javac") or "javac")
    build = ROOT / "build" / ("tests" if args.test_apk else "app")
    build.mkdir(parents=True, exist_ok=True)
    for part in ("classes", "dex", "gen", "res"):
        if (build / part).exists():
            shutil.rmtree(build / part)
        (build / part).mkdir(exist_ok=True)
    libraries = sorted((ROOT / "app/libs").glob("*.jar"))
    if args.without_onyx_sdk:
        libraries=[f for f in libraries if f.name.startswith(("commonmark","lunar-"))]
    src = ROOT / "app/src/main"
    manifest = src / "AndroidManifest.xml"
    if args.test_apk:
        manifest = ROOT / "tests/android/AndroidManifest.xml"
    res_zip = build / "resources.zip"
    run([build_tools / "aapt2", "compile", "--dir", src / "res", "-o", res_zip])
    package = "cn.inktodo.tests" if args.test_apk else "cn.inktodo.app"
    run([build_tools / "aapt2", "link", "-I", android, "--manifest", manifest,
         "--rename-manifest-package", package, "--min-sdk-version", "26", "--target-sdk-version", "32",
         "--version-code", version_code, "--version-name", version, "--java", build / "gen", "-o", build / "base.apk", res_zip])
    # Namespace is cn.inktodo, independent of the installation application ID.
    if args.test_apk:
        sources = sorted((ROOT / "tests/android").rglob("*.java"))
        classpath = str(android) + os.pathsep + str(ROOT / "build/app/classes")
    else:
        sources = sorted((src / "java").rglob("*.java")) + sorted((build / "gen").rglob("*.java"))
        if args.without_onyx_sdk:
            sources=[f for f in sources if f.name!="OnyxScreen.java"]
            sources.append(ROOT / "tools/diagnostics/sdk-free/cn/inktodo/OnyxScreen.java")
        classpath = str(android)
    classpath += os.pathsep + os.pathsep.join(str(f) for f in libraries)
    run([javac, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-classpath", classpath,
         "-d", build / "classes", *sources])
    classes_jar = build / "classes.jar"
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted((build / "classes").rglob("*.class")):
            z.write(f, f.relative_to(build / "classes").as_posix())
    d8 = [build_tools / "d8", "--release", "--min-api", "26", "--lib", android]
    if args.test_apk:
        d8 += ["--classpath", ROOT / "build/app/classes.jar"]
        for lib in libraries:
            d8 += ["--classpath", lib]
    run([*d8, "--output", build / "dex", classes_jar, *([] if args.test_apk else libraries)])
    unsigned = build / "unsigned.apk"
    shutil.copy2(build / "base.apk", unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        for f in sorted((build / "dex").glob("*.dex")):
            z.write(f, f.name)
        if not args.test_apk:
            for f in sorted((src / "assets").rglob("*")):
                if f.is_file():
                    z.write(f, "assets/" + f.relative_to(src / "assets").as_posix())
    run([build_tools / "zipalign", "-f", "-p", "4", unsigned, build / "aligned.apk"])
    signing = ROOT / ".signing"
    signing.mkdir(exist_ok=True)
    key = signing / "ink-todo-personal.p12"
    password_file = signing / "keystore-password.txt"
    signing_password = os.environ.get("INKTODO_KEYSTORE_PASSWORD")
    if not signing_password:
        if password_file.exists():
            signing_password = password_file.read_text().strip()
        elif key.exists():
            raise SystemExit("Signing password missing. Set INKTODO_KEYSTORE_PASSWORD or create .signing/keystore-password.txt locally; never commit either credential.")
        else:
            signing_password = secrets.token_urlsafe(32)
            with password_file.open("x") as file:
                password_file.chmod(0o600)
                file.write(signing_password + "\n")
    if not signing_password:
        raise SystemExit("Signing password must not be empty.")
    signing_env = os.environ.copy()
    signing_env["INKTODO_SIGNING_PASSWORD"] = signing_password
    if not key.exists():
        # Personal sideload signing identity; keep this file for future upgrade compatibility.
        keytool = javac.parent / "keytool"
        run([keytool, "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-storepass:env", "INKTODO_SIGNING_PASSWORD",
             "-keypass:env", "INKTODO_SIGNING_PASSWORD", "-alias", "inktodo", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-dname", "CN=Weiliao Local Build,O=Weiliao,C=CN"], env=signing_env)
        key.chmod(0o600)
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    out = dist / ("ink-todo-tests.apk" if args.test_apk else f"weiliao-{version}.apk")
    run([build_tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "inktodo", "--ks-pass", "env:INKTODO_SIGNING_PASSWORD",
         "--key-pass", "env:INKTODO_SIGNING_PASSWORD", "--out", out, build / "aligned.apk"], env=signing_env)
    run([build_tools / "apksigner", "verify", "--verbose", out])
    checksum = hashlib.sha256(out.read_bytes()).hexdigest()
    out.with_suffix(".apk.sha256").write_text(f"{checksum}  {out.name}\n")
    print(f"Built {out} ({out.stat().st_size:,} bytes)\nSHA-256: {checksum}")

if __name__ == "__main__":
    main()
