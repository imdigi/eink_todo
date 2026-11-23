# 未了 · Weiliao

**未了之事，次第而行。**

面向 Android 墨水屏设备的 Todoist 待办看板，适配 BOOX Leaf5+ 的左右分栏与低频刷新。当前源码版本为 **0.20.1**。

这是独立第三方应用，非由 Todoist 创建、关联或支持。

## 功能

- 每次启动或从后台重新打开时默认进入“今天”。左侧按“今天、收件箱、项目”排列，右侧连续滚动展示任务；今天包含“今天 / 逾期”两个分组。
- 父子任务缩进、默认展开、独立折叠；日期视图只展示本组范围内的子任务，并补充关联父级。
- 完成与删除、本地缓存、离线操作队列和前台增量同步。
- 完成后提供可点击的“撤回”提示，每条 3 秒，多个动作依次排队；提示期间暂缓提交，父级批量操作可以整组撤回。
- 屏保字体独立设置：设置 → 工具与账号 → 待办屏保，可导入 TTF，整张图片统一字体，支持恢复设备默认宋体；确认使用后立即重新生成并提交系统。
- 重要项目屏保：设置 → 工具与账号 → 待办屏保，可从已同步的有效项目中选择一个；启用后竖屏左右分栏，右侧只显示未完成主任务及描述（最多两行），隐藏子任务和日期；已在左侧展示的任务不重复显示。未选择或项目已归档/删除时恢复单栏；演示与真实账号分别保存。
- 节气锁屏壁纸：大号宋体节气名称、生成日期、展开的任务层级；已同步内容变化后自动更新。
- 首页题句支持点击编辑、基础 Markdown 和换行；支持导入 TTF 作为任务正文字体。
- 全屏分级设置：白底列表、名称与说明上下排列、黑白开关、固定底部返回与上下翻页；所有普通设置即时保存，字体导入先预览确认。返回待办保留原项目、展开状态与滚动位置。
- 黑白界面、固定侧栏操作区；“设置 / 同步”位于左下角，状态文字变化不会推动按钮。

宽屏至少 600 dp 时采用左右分栏；支持 1264×1680、1680×1264 及窄屏布局。最低 Android 8.0（API 26），编译 SDK 35，目标 SDK 32。目前以侧载 APK 使用。

## 连接 Todoist

1. 构建 APK 并安装到设备，打开“未了”。
2. 在 [Todoist 网页版开发者设置](https://app.todoist.com/app/settings/integrations/developer) 中复制个人 API Token。
3. 在应用的“连接 Todoist”页面输入 Token，保存并同步。个人免费账号可用，无需创建开发者应用。
4. 也可先使用独立的演示模式体验界面。

Token 只保存在设备上，并由 Android Keystore 加密。不要把 Token、GitHub 授权凭据或 APK 签名文件写进源码、提交记录和 issue。

## 在文石设备上使用

建议把文石“应用引擎优化 → 个性化 → 刷新模式”设为 **标准**。极速模式可能让文字和灰阶边缘出现明显锯齿。

应用默认沿用系统刷新策略，可选 SDK 刷新增强和手动清残影。未启用增强时，不调用 SDK 刷新或重置接口。设置屏保仍使用官方文石 SDK；不同设备和固件的兼容性需要实机验证。

屏保是一张静态图片，不能在休眠时交互。自动更新以本机已同步的缓存为基础，不保证深度休眠时唤醒联网。图片会保存在设备公共相册 `Pictures/InkTodo`，其中可能包含任务内容。长期保留的源图固定覆盖 `InkTodo-current.png`。文石会缓存同一路径的图片，因此内容变化时另生成唯一文件名的临时导入图，提交给文石后延迟清理；不会持续积累历史图片。升级后也会清理本应用生成的旧版历史图片。任务同步只有屏保内容变化时才重新生成并提交文石 SDK；仅同步时间变化不会更新图片。用户修改屏保设置则立即生成并提交，不受任务自动更新开关和内容去重限制。

## 同步与撤回

通过 Todoist `/api/v1/sync` 获取任务、项目及增量游标，使用 `item_close`、`item_delete` 提交操作。每条操作保存固定 UUID，超时后沿用原编号重试，避免重复执行。

完成后先更新本机列表。正在展示或排队等待“撤回”提示的完成命令会被暂时保护，提示结束后才进入同步。父任务按一次父级动作撤回，子任务仅撤回自身；今天视图按父级完成的本组子任务作为一组撤回。离开应用时关闭提示，未撤回的操作进入正常同步流程。

从锁屏或后台返回时，先等待系统确认网络可用：每秒本地检查一次，最多 15 秒。期间显示“等待网络…”，超时显示“网络未连接”并停止轮询；网络恢复后自动同步。离开应用时停止等待和网络监听，不唤醒设备。同步期间优先显示“同步中…”，避免旧失败信息遮住当前状态。

默认在前台每 10 分钟同步，可设置 5/10/15/30 分钟。网络故障后的手动重试保留 3 秒防连点；服务器限流和 `Retry-After` 对手动、自动请求都生效。失败原因保留在侧栏，长按可以查看完整状态。

## 构建

需要 Python 3、JDK 11 或更高版本、Android SDK Platform 35 和 Build Tools 35.0.0。依赖 JAR 位于 `app/libs/`，不使用 Gradle 下载运行库。

```sh
git clone https://github.com/imdigi/eink_todo.git
cd eink_todo
export ANDROID_HOME=/path/to/Android/sdk
export JAVA_HOME=/path/to/jdk
python3 tools/build.py
```

可使用 `ANDROID_JAR` 指定 `android.jar`，或使用 `ANDROID_BUILD_TOOLS` 指定构建工具目录。

输出为 `dist/weiliao-0.20.1.apk` 及 SHA-256 校验文件。首次构建会在 `.signing/` 内生成本地签名私钥和随机密码；这些文件、安装包和截图均不进入 Git。

已有签名可通过 `.signing/keystore-password.txt` 或 `INKTODO_KEYSTORE_PASSWORD` 环境变量提供密码。构建日志不会输出密码。**请私密备份原签名和密码，后续覆盖安装需要使用同一签名。**

```sh
adb -s YOUR_DEVICE install -r dist/weiliao-0.20.1.apk
```

诊断构建 `python3 tools/build.py --without-onyx-sdk` 排除文石 SDK，并临时禁用屏保和 SDK 刷新增强。

## 验证

```sh
mkdir -p .toolchain
curl -fL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o .toolchain/test-json.jar
python3 tools/test.py
```

[JVM 与 Android 验证说明](docs/TESTING.md)包含隔离数据库、三秒撤回队列、同步重试及屏保检查的运行方法。

提交前检查暂存内容：

```sh
python3 tools/check_private_files.py
```

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/cn/inktodo/` | 原生界面、同步、缓存、撤回、字体、屏保与刷新适配 |
| `app/src/main/res/` | 黑白主题和图标 |
| `app/libs/` | 固定版本的第三方依赖 |
| `app/src/main/assets/legal/` | 第三方许可和来源说明 |
| `tools/` | 构建、隐私检查及本地验证工具 |
| `tests/jvm/` | 协议与业务逻辑检查 |
| `tests/android/` | 隔离数据的设备验证及手动工具 |

## 隐私与第三方依赖

应用使用 `ACCESS_NETWORK_STATE` 读取本机网络就绪状态，不需要运行时授权。

详情见 [隐私与本地文件](docs/PRIVACY.md)。仓库不包含个人 Token、签名私钥、真实任务数据库、真机截图或本地操作日志。

第三方组件包括文石 device SDK、CommonMark、lunar-java 等，保留各自许可；来源见 [NOTICE](app/src/main/assets/legal/NOTICE.txt)，依赖文件的 SHA-256 见 [DEPENDENCIES.json](docs/DEPENDENCIES.json)。
