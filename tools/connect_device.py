#!/usr/bin/env python3
"""Enter a user-provided token through a hidden prompt; never persist or print it."""
import argparse
import getpass
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument("--serial", required=True)
args = p.parse_args()
adb = str(Path.home() / "Library/Android/sdk/platform-tools/adb")

def run(*parts):
    return subprocess.run([adb, "-s", args.serial, *parts], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode("utf-8")

def ui():
    run("shell", "uiautomator", "dump", "/sdcard/ink-connection-check.xml")
    return ET.fromstring(run("shell", "cat", "/sdcard/ink-connection-check.xml"))

def tap(n):
    x1,y1,x2,y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    run("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

def by_text(nodes, label):
    return next(n for n in nodes.iter("node") if n.get("package") == "cn.inktodo.app" and n.get("text") == label)

nodes = ui()
if any(n.get("password") == "true" for n in nodes.iter("node")):
    tap(by_text(nodes, "取消"))
    nodes = ui()
tap(by_text(nodes, "设置"))
nodes = ui()
tap(by_text(nodes, "工具与账号"))
nodes = ui()
tap(by_text(nodes, "连接管理"))
nodes = ui()
fields = [n for n in nodes.iter("node") if n.get("class") == "android.widget.EditText" and n.get("package") == "cn.inktodo.app" and n.get("password") == "true"]
buttons = [n for n in nodes.iter("node") if n.get("text") == "保存并连接"]
if len(fields) != 1 or len(buttons) != 1:
    raise SystemExit("Expected Ink Todo's secure connection dialog. No credential entered.")
secret = getpass.getpass("Todoist token (hidden): ")
if not re.fullmatch(r"[A-Za-z0-9_\-]{16,}", secret):
    raise SystemExit("Invalid token format. No credential entered.")
tap(fields[0])
# No terminal echo, no files, no credential-bearing command logging.
run("shell", "input", "text", secret)
secret = None
# Keyboard appearance can move the dialog: always locate the submit button again.
tap(by_text(ui(), "保存并连接"))
for _ in range(12):
    time.sleep(1)
    nodes = ui()
    texts = [n.get("text", "") for n in nodes.iter("node") if n.get("package") == "cn.inktodo.app"]
    status = next((s for s in texts if "上次同步" in s), "")
    if status:
        counts = [s for s in texts if re.fullmatch(r"\d+ 项", s)]
        project_buttons = [n for n in nodes.iter("node") if n.get("class") == "android.widget.Button" and n.get("text") not in {"设置", "收件箱", "今天", "逾期", "同步", "操作记录", "重绘", "清残影", "⋯"}]
        print("Connected successfully; server sync confirmed.", flush=True)
        if counts: print("Visible active task count:", counts[0], flush=True)
        print("Project navigation entries:", len(project_buttons), flush=True)
        if "视图" in texts and project_buttons:
            tap(project_buttons[0]); time.sleep(.5); project_ui = ui()
            print("Project selection opens task pane:", any(n.get("text") == project_buttons[0].get("text") and n.get("class") == "android.widget.TextView" for n in project_ui.iter("node")), flush=True)
            inbox_button = next(n for n in project_ui.iter("node") if n.get("text") == "收件箱" and n.get("class") == "android.widget.Button")
            tap(inbox_button)
        break
    known_errors = [s for s in texts if any(k in s for k in ["Token 无效", "网络或同步失败", "账号或权限受限", "服务暂不可用", "请求暂时受限"])]
    if known_errors:
        print("App reported a connection error; credential remains encrypted on device.", flush=True)
        raise SystemExit(2)
else:
    raise SystemExit("No successful sync confirmed in the verification window.")
