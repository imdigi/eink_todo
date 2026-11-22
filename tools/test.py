#!/usr/bin/env python3
import os
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
jar = Path(os.environ.get("JSON_JAR", str(root / ".toolchain/test-json.jar")))
if not jar.exists():
    raise SystemExit("Missing test-only org.json library. See README.md.")
out = root / "build/jvm-tests"
out.mkdir(parents=True, exist_ok=True)
src = root / "app/src/main/java/cn/inktodo"
classpath = str(jar) + os.pathsep + str(root / "app/libs/lunar-1.7.8.jar")
subprocess.run([shutil.which("javac"), "-encoding", "UTF-8", "-cp", classpath, "-d", str(out), str(src / "Core.java"), str(src / "ApiClient.java"), str(src / "SyncRetry.java"), str(src / "SnapshotModel.java"), str(src / "SolarTerm.java"), str(root / "tests/jvm/cn/inktodo/ProtocolTests.java")], check=True)
subprocess.run([shutil.which("java"), "-cp", str(out) + os.pathsep + classpath, "cn.inktodo.ProtocolTests"], check=True)
