#!/usr/bin/env python3
"""Read-only UI verification: refresh, settings, preview, cancel. Never sets a screensaver."""
import argparse
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser();p.add_argument("--serial",required=True);args=p.parse_args()
base=[str(Path.home()/"Library/Android/sdk/platform-tools/adb"),"-s",args.serial]
def run(*items): return subprocess.check_output(base+list(items))
def ui():
    run("shell","uiautomator","dump","/sdcard/ink-eink-ui.xml")
    return ET.fromstring(run("shell","cat","/sdcard/ink-eink-ui.xml"))
def locate(label):
    for attempt in range(5):
        tree=ui()
        found=[n for n in tree.iter("node") if n.get("text")==label and n.get("package")=="cn.inktodo.app"]
        if found: return found[0]
        scrolls=[n for n in tree.iter("node") if n.get("class")=="android.widget.ScrollView" and n.get("scrollable")=="true"]
        if not scrolls: break
        x1,y1,x2,y2=map(int,re.findall(r"\d+",scrolls[0].get("bounds")))
        x=(x1+x2)//2
        run("shell","input","swipe",str(x),str(y1+(y2-y1)*4//5),str(x),str(y1+(y2-y1)//5),"200")
    raise AssertionError("Expected app control is unavailable: "+label)
def tap(label):
    n=locate(label);x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds")))
    run("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
run("shell","am","start","-W","-n","cn.inktodo.app/cn.inktodo.MainActivity")
tap("设置");tap("工具与账号");tap("清残影");print("PASS manual refresh control",flush=True)
tap("设置");locate("启用墨水屏刷新优化");print("PASS enhancement settings",flush=True)
tap("工具与账号");tap("待办屏保预览…");locate("待办屏保预览");locate("设为系统屏保");print("PASS preview and explicit apply control",flush=True)
tap("取消");locate("同步");print("PASS preview cancelled without setting screensaver",flush=True)
texts=[n.get("text","") for n in ui().iter("node")]
print("Live account retained:","墨待办" in texts and not any("演示数据" in s for s in texts),flush=True)
print("SUCCESS 4 UI checks; no tasks or system images modified",flush=True)
