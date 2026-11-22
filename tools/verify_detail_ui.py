#!/usr/bin/env python3
"""Verify 0.4 UI using isolated demo data and restore the original account mode."""
import argparse,re,subprocess
from pathlib import Path
import xml.etree.ElementTree as E
p=argparse.ArgumentParser();p.add_argument("--serial",required=True);args=p.parse_args()
root=Path(__file__).resolve().parents[1];base=[str(Path.home()/"Library/Android/sdk/platform-tools/adb"),"-s",args.serial]
def run(*a):return subprocess.check_output(base+list(a))
def ui():
    run("shell","uiautomator","dump","/sdcard/ink-detail-ui.xml")
    return E.fromstring(run("shell","cat","/sdcard/ink-detail-ui.xml"))
def box(n):return tuple(map(int,re.findall(r"\d+",n.get("bounds"))))
def find(t,label,button=False):
    return next(n for n in t.iter("node") if n.get("text")==label and (not button or n.get("class")=="android.widget.Button"))
def tap(label,button=True):
    n=find(ui(),label,button);a,b,c,d=box(n);run("shell","input","tap",str((a+c)//2),str((b+d)//2))
def rows(t):
    return [n for n in t.iter("node") if list(n) and list(n)[0].get("class")=="android.widget.CheckBox" and list(n)[0].get("content-desc","").startswith("完成：")]
def screenshot(name):
    out=root/"dist/screenshots"/name;out.parent.mkdir(exist_ok=True,parents=True);out.write_bytes(run("exec-out","screencap","-p"))
run("shell","am","start","-W","-n","cn.inktodo.app/cn.inktodo.MainActivity")
original=ui();was_demo=any(n.get("text")=="墨待办 · 演示" for n in original.iter("node"))
if not was_demo:tap("设置");tap("工具与账号");tap("进入演示模式")
t=ui();assert any(n.get("text")=="墨待办 · 演示" for n in t.iter("node"))
tap("收件箱");t=ui();settings=find(t,"设置",True);sync=find(t,"同步",True)
assert box(sync)[0]>box(settings)[2] and box(sync)[1]==box(settings)[1]
assert not any(n.get("text") in {"操作记录","清残影","⋯","..."} for n in t.iter("node"))
print("PASS top-right sync, hidden utilities and removed item menu",flush=True)
nav=next(n for n in t.iter("node") if n.get("class")=="android.widget.ScrollView" and any(x.get("text")=="视图" for x in n.iter("node")))
buttons=[n for n in nav.iter("node") if n.get("class")=="android.widget.Button"]
assert [n.get("text") for n in buttons[:2]]==["收件箱","今天"]
for b in buttons:assert re.fullmatch(re.escape(b.get("text"))+r"，\d+项",b.get("content-desc",""))
print("PASS view and project counters are present",flush=True)
rs=rows(t);assert len(rs)>=2
for r in rs[:2]:
    ch=list(r);texts=[n.get("text") for n in ch[1].iter("node") if n.get("class")=="android.widget.TextView" and n.get("text")]
    assert len(ch)==2 and len(texts)==3 and texts[2]=="收件箱" and texts[1] in {"先收集想法，稍后再安排时间和项目。","补充背景、参考链接和需要注意的细节。"}
print("PASS descriptions precede project labels in views, without duplicate buttons",flush=True)
scroll=next(n for n in t.iter("node") if n.get("class")=="android.widget.ScrollView" and rows(n))
assert box(scroll)[3]>=max(box(n)[3] for n in t.iter("node"))-40
print("PASS task pane uses space formerly occupied by bottom bar",flush=True)
screenshot("ui-0.4.0-inbox-demo.png")
tap("阅读");t=ui();rs=rows(t);assert rs
for r in rs:
    texts=[n.get("text") for n in list(r)[1].iter("node") if n.get("class")=="android.widget.TextView"]
    assert "阅读" not in texts and "这是演示任务，操作不会影响你的 Todoist。" in texts
print("PASS project view descriptions are shown without repeated project name",flush=True)
screenshot("ui-0.4.0-project-demo.png")
title=next(n for n in list(rs[0])[1].iter("node") if n.get("class")=="android.widget.TextView")
a,b,c,d=box(title);run("shell","input","tap",str((a+c)//2),str((b+d)//2));t=ui();find(t,"删除…",True);find(t,"关闭",True);tap("关闭")
print("PASS tapping item still opens details and delete entry",flush=True)
tap("设置");t=ui();find(t,"显示与同步",True);find(t,"工具与账号",True);find(t,"任务字号")
screenshot("ui-0.4.0-settings-display.png")
before=find(t,"保持屏幕唤醒").get("checked");tap("保持屏幕唤醒",False)
tap("工具与账号");t=ui();find(t,"操作记录",True);find(t,"清残影",True);find(t,"连接管理",True)
screenshot("ui-0.4.0-settings-tools.png")
tap("显示与同步");assert find(ui(),"保持屏幕唤醒").get("checked")!=before
tap("取消");tap("设置");assert find(ui(),"保持屏幕唤醒").get("checked")==before;tap("保存")
print("PASS settings tabs retain edits, cancel discards and save closes cleanly",flush=True)
print("PASS utilities moved into settings",flush=True)
if not was_demo:tap("设置");tap("工具与账号");tap("返回真实账号")
tap("收件箱")
print("SUCCESS 8 UI checks; original account restored, no real tasks modified",flush=True)
