#!/usr/bin/env python3
"""Run demo-only UI checks on the disposable emulator, never on a real device."""
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb")
SERIAL = "emulator-5580"
OUT = ROOT / "dist/screenshots"
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, binary=False):
    p = subprocess.run([ADB, "-s", SERIAL, *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return p.stdout if binary else p.stdout.decode("utf-8")

def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ink-ui.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/ink-ui.xml"))

def find(label, attr="text"):
    matches = [n for n in ui().iter("node") if n.get(attr) == label and n.get("enabled") == "true"]
    if not matches:
        raise AssertionError(f"UI element missing: {label}")
    return matches[0]

def tap(label, attr="text"):
    n = find(label, attr)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.25)

def check(label):
    find(label)
    print("PASS UI", label, flush=True)

def shot(name):
    (OUT / name).write_bytes(adb("exec-out", "screencap", "-p", binary=True))

adb("shell", "am", "start", "-W", "-n", "cn.inktodo.app/cn.inktodo.MainActivity")
nodes = ui()
if any(n.get("text") == "先体验演示模式" for n in nodes.iter("node")):
    tap("先体验演示模式")
check("墨待办 · 演示")  # Refuse to manipulate tasks unless the UI explicitly says demo.
check("视图")
check("2 项")
shot("demo-portrait-1264x1680.png")
tap("阅读"); check("4 项")
tap("今天"); check("4 项")
tap("完成：读完今天的二十页", "content-desc"); check("3 项")
tap("完成：整理这周的阅读笔记", "content-desc"); check("2 项")
tap("阅读"); check("4 项")
tap("下楼散步半小时"); tap("删除…"); check("删除这项任务？"); tap("删除任务"); check("3 项")
adb("shell", "am", "force-stop", "cn.inktodo.app")
adb("shell", "am", "start", "-W", "-n", "cn.inktodo.app/cn.inktodo.MainActivity")
check("墨待办 · 演示"); check("3 项"); tap("收件箱"); check("2 项")
adb("shell", "wm", "size", "1680x1264"); time.sleep(1)
check("视图"); check("阅读"); check("2 项")
shot("demo-landscape-1680x1264.png")
adb("shell", "wm", "size", "720x1280"); time.sleep(1)
check("项目"); check("2 项")
assert not any(n.get("text") == "视图" for n in ui().iter("node"))
print("PASS UI narrow layout collapses sidebar", flush=True)
shot("demo-narrow-720x1280.png")
adb("shell", "wm", "size", "1680x1264"); time.sleep(1)
check("视图")
print("SUCCESS demo UI flow, filtering, recurring completion, deletion, restart and responsive layouts", flush=True)
