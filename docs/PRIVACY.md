# 隐私与本地文件

未了直接与 Todoist 官方 API 通信，不经过自建服务器。个人 API Token 由用户在设备上输入，使用 Android Keystore 加密保存；Token 不内置于源码或安装包。

以下内容仅保留在本机，Git 忽略规则会排除：

- `.signing/`：APK 签名私钥及密码。
- `.toolchain/`：本地 SDK、下载工具及测试依赖。
- `build/`、`dist/`：构建产物、截图、日志、临时屏保与操作备份。
- `.env`、数据库文件、私钥文件、Python 缓存。
- `PLAN.md`、`TEST_REPORT.md`：可能涉及设备和会话的本地开发记录。

屏保图片和手动截图可能包含真实任务内容。不要把这些图片或设备导出的配置、数据库上传到仓库；需要展示效果时使用演示数据，并先检查图片内容。

## 提交前检查

```sh
git add README.md .gitignore app tests tools docs
python3 tools/check_private_files.py
git diff --cached --stat
```

检查器读取 Git 暂存内容，阻止常见私密文件、私钥、凭据格式和个人目录路径。报错只显示文件、行号及类型，不打印匹配的值。检查通过后，仍应确认待提交文件清单符合预期。

## APK 签名

首次构建会在 `.signing/` 内生成本地签名和随机密码。已有签名可通过本地密码文件或 `INKTODO_KEYSTORE_PASSWORD` 环境变量使用。构建命令只向签名工具传递环境变量名称，避免密码出现在命令日志中。

备份签名私钥和密码时使用私密存储；两者都不要提交到 Git。保留原签名才能覆盖升级已安装的 APK。
