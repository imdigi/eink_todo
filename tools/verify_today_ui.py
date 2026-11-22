#!/usr/bin/env python3
"""Read-only verification of merged today groups and same-row task metadata."""
import argparse,re,subprocess
from pathlib import Path
import xml.etree.ElementTree as E
p=argparse.ArgumentParser();p.add_argument("--serial",required=True);args=p.parse_args()
root=Path(__file__).resolve().parents[1];base=[str(Path.home()/"Library/Android/sdk/platform-tools/adb"),"-s",args.serial]
def run(*a):return subprocess.check_output(base+list(a))
def ui():
    run("shell","uiautomator","dump","/sdcard/ink-today-ui.xml")
    return E.fromstring(run("shell","cat","/sdcard/ink-today-ui.xml"))
def bounds(n):return tuple(map(int,re.findall(r"\d+",n.get("bounds"))))
def tap(label):
    n=next(n for n in ui().iter("node") if n.get("class")=="android.widget.Button" and n.get("text")==label)
    a,b,c,d=bounds(n);run("shell","input","tap",str((a+c)//2),str((b+d)//2))
def task_rows(t):return [n for n in t.iter("node") if list(n) and list(n)[0].get("class")=="android.widget.CheckBox" and list(n)[0].get("content-desc","").startswith("完成：")]
def sections(t):
    found=[]
    for n in t.iter("node"):
        ch=list(n)
        if len(ch)==2 and ch[0].get("class")=="android.widget.TextView" and ch[0].get("text") in {"今天","逾期"} and ch[1].get("text","").isdigit():found.append((ch[0].get("text"),int(ch[1].get("text"))))
    return found
def shot(name):
    (root/"dist/screenshots"/name).write_bytes(run("exec-out","screencap","-p"))
run("shell","am","start","-W","-n","cn.inktodo.app/cn.inktodo.MainActivity")
was_demo=any(n.get("text")=="墨待办 · 演示" for n in ui().iter("node"))
if not was_demo:tap("设置");tap("工具与账号");tap("进入演示模式")
tap("今天");t=ui()
nav=next(n for n in t.iter("node") if n.get("class")=="android.widget.ScrollView" and any(x.get("text")=="视图" for x in n.iter("node")))
buttons=[n for n in nav.iter("node") if n.get("class")=="android.widget.Button"]
assert [n.get("text") for n in buttons[:2]]==["收件箱","今天"] and not any(n.get("text")=="逾期" for n in buttons)
print("PASS overdue navigation removed, today retained",flush=True)
badge=int(re.search(r"，(\d+)项",buttons[1].get("content-desc")).group(1))
seen=sections(t);assert seen and seen[0][0]=="今天"
aligned=False
for r in task_rows(t):
    words=list(r)[1];ch=list(words)
    if not ch:continue
    metadata=ch[-1];parts=list(metadata)
    if len(parts)==2 and all(n.get("class")=="android.widget.TextView" and n.get("text","") for n in parts):
        due,project=parts;assert bounds(due)[0]<bounds(project)[0] and abs(bounds(due)[1]-bounds(project)[1])<=2 and bounds(project)[2]==bounds(metadata)[2]
        aligned=True
assert aligned
print("PASS due date left and project right on same row",flush=True)
for _ in range(6):
    if any(name=="逾期" for name,n in seen):break
    scroll=next(n for n in t.iter("node") if n.get("class")=="android.widget.ScrollView" and task_rows(n))
    a,b,c,d=bounds(scroll);x=(a+c)//2;run("shell","input","swipe",str(x),str(b+(d-b)*4//5),str(x),str(b+(d-b)//5),"250")
    t=ui();seen+=sections(t)
groups=dict(seen);assert "逾期" in groups and groups["今天"]+groups["逾期"]==badge
print("PASS today section above overdue and badge equals both section totals",flush=True)
tap("今天");shot("ui-0.5.0-today-demo.png")
tap("阅读");t=ui()
for r in task_rows(t):
    texts=[n.get("text") for n in list(r)[1].iter("node") if n.get("class")=="android.widget.TextView"]
    assert "阅读" not in texts
print("PASS project view does not repeat project name",flush=True)
shot("ui-0.5.0-project-demo.png")
if not was_demo:tap("设置");tap("工具与账号");tap("返回真实账号")
tap("今天")
print("SUCCESS 4 UI checks; original account mode restored; no real tasks modified",flush=True)
