# 构建与验证

## JVM 测试

```sh
mkdir -p .toolchain
curl -fL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o .toolchain/test-json.jar
python3 tools/test.py
```

目前包含 118 项协议与逻辑检查，覆盖任务筛选、父子层级、日期分组、命令 UUID、同步响应、重试策略、节气日期与屏保指纹。

## Android 验证

先构建正式 APK，再构建验证 APK：

```sh
python3 tools/build.py
python3 tools/build.py --test-apk
adb -s YOUR_DEVICE install -r dist/weiliao-0.17.0.apk
adb -s YOUR_DEVICE install -r dist/ink-todo-tests.apk
adb -s YOUR_DEVICE shell am instrument -w cn.inktodo.tests/cn.inktodo.VerificationRunner
```

主要验证入口：

| 入口 | 范围 |
| --- | --- |
| `VerificationRunner` | 隔离数据库、离线队列、原 UUID 重试、加密存储；29 项 |
| `UndoVerificationRunner` | 隔离任务、父子撤回、三秒提示队列、按钮位置稳定；34 项 |
| `NetworkVerificationRunner` | 注入网络和时钟的唤醒等待、每秒本地检查、15 秒超时、恢复及离开取消；14 项 |
| `SyncVerificationRunner` | 演示数据库下的同步按钮、限流和错误提示、同步中状态优先；13 项 |
| `ScreensaverUpdateRunner` | 隔离数据与模拟提交器下的内容变化、固定路径覆盖、去重、重试和 SDK 路径缓存回归；28 项 |
| `ScreensaverFontUiRunner` | 屏保字体文件选择器、取消、保存及恢复默认；7 项 |
| `ScreensaverFontRunner` | 独立字体存储、恢复默认宋体、错误回退及图片差异；6 项，需 `-e font` 指定设备可读的 TTF；结束后保留导入字体 |
| `ScreensaverVerificationRunner` | PNG 编码、固定路径原子覆盖、旧图片清理和完整性与唯一临时导入；21 项 |
| `TitleVerificationRunner` | Markdown 标题编辑、保存、取消及恢复；结束后恢复设置 |

UI 验证可能启动真实应用并触发正常的前台同步。测试任务写入使用隔离数据库，建议在专用设备或演示环境运行。

`ScreensaverResultRunner`、`ScreensaverReadProbe`、`ScreensaverDeliveryRunner` 和 `PhotoScreensaverRunner` 会替换设备系统屏保，属于手动工具，不作为默认自动测试运行。摄影工具支持 `-e mode demo` 与 `-e mode restore`，会在本机保存恢复配置。

自动检查无法替代对实体墨水屏清晰度、残影、刷新模式和休眠行为的观察。文石“应用引擎优化”中的刷新模式建议设为“标准”。

`ScreensaverDeliveryRunner` 使用 `-e phase one`、`-e phase two` 连续提交两份隔离快照；每次锁屏后应将截图与 SOURCE_PATH 图像逐像素比较，验证绕过文石同路径缓存。结束时必须运行 `-e phase restore`，恢复真实缓存的屏保并开启自动更新，清理隔离样本。它不会修改真实 Todoist 任务。
