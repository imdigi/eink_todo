#!/usr/bin/env python3
"""Verify navigation and scrolling without modifying real tasks; capture demo only."""
import argparse
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser();p.add_argument("--serial",required=True);args=p.parse_args()
root=Path(__file__).resolve().parents[1]
base=[str(Path.home()/"Library/Android/sdk/platform-tools/adb"),"-s",args.serial]
def run(*parts): return subprocess.check_output(base+list(parts))
def ui():
    run("shell","uiautomator","dump","/sdcard/ink-navigation-ui.xml")
    return ET.fromstring(run("shell","cat","/sdcard/ink-navigation-ui.xml"))
def bounds(n): return tuple(map(int,re.findall(r"\d+",n.get("bounds"))))
def tap(label):
    tree=ui()
    matches=[n for n in tree.iter("node") if n.get("text")==label and n.get("class")=="android.widget.Button"]
    if not matches and label in {"进入演示模式","返回真实账号"}:
        tabs=[n for n in tree.iter("node") if n.get("text")=="工具与账号" and n.get("class")=="android.widget.Button"]
        if tabs:
            x1,y1,x2,y2=bounds(tabs[0]);run("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2));tree=ui()
            matches=[n for n in tree.iter("node") if n.get("text")==label and n.get("class")=="android.widget.Button"]
    if not matches:
        buttons={n.get("text"):n for n in tree.iter("node") if n.get("class")=="android.widget.Button"}
        if {"完成","删除…","关闭"}.issubset(buttons):
            x1,y1,x2,y2=bounds(buttons["关闭"]);run("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
            tree=ui();matches=[n for n in tree.iter("node") if n.get("text")==label and n.get("class")=="android.widget.Button"]
    if not matches: raise AssertionError("Expected navigation control unavailable: "+label)
    n=matches[0]
    x1,y1,x2,y2=bounds(n);run("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
def sidebar(t):
    return next(n for n in t.iter("node") if n.get("class")=="android.widget.ScrollView" and any(c.get("text")=="视图" for c in n.iter("node")))
def checks(t): return [n for n in t.iter("node") if n.get("class")=="android.widget.CheckBox" and n.get("content-desc","").startswith("完成：")]
run("shell","am","start","-W","-n","cn.inktodo.app/cn.inktodo.MainActivity")
before=ui();was_demo=any(n.get("text")=="墨待办 · 演示" for n in before.iter("node"))
tap("收件箱");t=ui();nav=sidebar(t)
labels=[n.get("text") for n in nav.iter("node") if n.get("class")=="android.widget.Button"]
assert labels[:2]==["收件箱","今天"] and labels.count("收件箱")==1 and "逾期" not in labels
assert not any(s in {"Inbox","inbox","全部任务","全部","今日"} for s in labels)
print("PASS ordered views and inbox excluded from projects",flush=True)
assert not any(n.get("text") in {"上一页","下一页"} for n in t.iter("node"))
print("PASS pagination controls removed",flush=True)
checked_alignment=False;scrolled=False
for label in ["收件箱","今天"] + labels[2:]:
    tap(label);t=ui()
    for node in t.iter("node"):
        children=list(node)
        if children and children[0].get("class")=="android.widget.CheckBox" and len(children)>1:
            title=next((n for n in children[1].iter("node") if n.get("class")=="android.widget.TextView"),None)
            if title is not None:
                assert abs(bounds(children[0])[1]-bounds(title)[1])<=2
                checked_alignment=True
    scroll=next((n for n in t.iter("node") if n.get("class")=="android.widget.ScrollView" and n.get("scrollable")=="true" and checks(n)),None)
    if scroll is not None:
        old=[n.get("content-desc") for n in checks(t)];nav_bounds=[bounds(n) for n in sidebar(t).iter("node") if n.get("class")=="android.widget.Button"]
        x1,y1,x2,y2=bounds(scroll);x=(x1+x2)//2
        run("shell","input","swipe",str(x),str(y1+(y2-y1)*4//5),str(x),str(y1+(y2-y1)//5),"300")
        after=ui();assert old!=[n.get("content-desc") for n in checks(after)]
        assert nav_bounds==[bounds(n) for n in sidebar(after).iter("node") if n.get("class")=="android.widget.Button"]
        scrolled=True;break
assert checked_alignment
print("PASS native checkboxes top aligned with item text",flush=True)
print("PASS task pane scrolls independently" if scrolled else "INFO current view contents fit viewport",flush=True)
tap("收件箱")
if not was_demo:
    tap("设置");tap("进入演示模式")
t=ui();assert any(n.get("text")=="墨待办 · 演示" for n in t.iter("node"))
tap("收件箱")
out=root/"dist/screenshots/navigation-0.3.0-demo.png";out.parent.mkdir(exist_ok=True,parents=True)
out.write_bytes(run("exec-out","screencap","-p"))
print("PASS demo-only screenshot captured",flush=True)
if not was_demo:
    tap("设置");tap("返回真实账号");tap("收件箱")
print("SUCCESS navigation UI verification; original account mode restored",flush=True)
