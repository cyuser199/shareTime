# 云端版本 0.3.1

手机服务器地址：`https://cyadmin.xyz/sharetime`。已迁移至 114.215.203.204；旧版覆盖升级自动切换地址并保留配对和本机记录。安装包 `deliverables/ShareTime-0.3.1-debug.apk`。详见 [云端迁移与验证](docs/cloud-migration.md)。

以下早期部署说明保留作本地开发参考，实际线上入口以上述云端地址为准。

# 此刻 · ShareTime

两个朋友之间的 Android 图片记录应用。最低 Android 8.0（API 26），编译及目标 Android 17（API 37）。目前为 0.3.0 测试版。此前版本已在 Android 11 与 Android 16 双机联调；本次新增功能的验证范围见 [0.3.0 说明](docs/release-0.3.0.md)。

## 使用

1. 两台手机安装 `deliverables/ShareTime-0.3.0-debug.apk`，连接能访问 Debian 的局域网。覆盖安装可保留配对和本机历史。
2. 首次启动先阅读并确认使用协议，然后填写昵称，服务器默认 `https://192.168.1.191:8443`。本机服务器的专用 CA 公钥已经内置，不需要关闭证书验证或给手机安装全局 CA。
3. 一人生成 8 位配对码，另一人输入；生成端确认后完成绑定。可在设置页对照双方安全码。
4. 点击“一起记录”。申请人立即进入准备页，好友收到邀请，可以同意或拒绝。
5. 每人拍照或截图、预览、确认提交；双方均提交并解密成功后，两张图片自动加密存入本机时间轴。
6. 当天第一次完成时播放祝贺动画，然后展示竖向时间轴。点时间轴圆点或卡片可查看这次双方的记录，后续记录继续按次数排列。
7. 点击“日历”，选择某一天并确认，可查看当天的时间轴；“回到今天”返回今天。
8. 设置 → 选择背景图片，可使用系统照片选择器设置自己的背景；支持恢复默认背景，背景不会同步给好友。
9. 记录详情支持另存到相册，也可确认删除本机这一条记录。删除本机历史不影响好友或相册中的副本。

系统相机由本人操作。屏幕截图每次请求系统授权，授权后有 5 秒切换时间；只截取一帧即停止投屏服务。也可使用手机自带截图，再通过系统选择器选择图片。受保护页面可能黑屏，不绕过系统限制。

## 通知与网络边界

- 应用打开时实时接收邀请。设置中的“保持接收邀请”由用户主动开启，使用 Android `specialUse` 前台服务并显示持续通知。
- 本版未接厂商离线推送；系统终止进程、强制停止、拒绝通知或严格省电设置可能导致无法及时提醒。不使用后台自动启动相机或全屏弹窗。可选授权悬浮窗显示 15 秒邀请卡片；锁屏和勿扰时使用系统通知。
- 好友未连接也可以发起邀请，进入等待页；邀请最多等待 24 小时，同意后每轮最多 10 分钟。服务器重启会清除待处理邀请。
- `192.168.1.191` 只适合局域网/VPN。外网服务需要另行配置可达地址、受信任 TLS 证书和网络入口，尚未配置。
- 双方提交是同一轮记录，不是严格同时曝光；手动选择图片也不能证明内容刚刚产生。

## 隐私与服务端状态

- ECDH P-256 + HKDF-SHA256 派生每轮密钥，AES-256-GCM 加密图片；密钥和认证凭据由手机 Android Keystore 包装保存，并排除备份。
- 服务端无法用其签名密钥解密图片。安全码可用于双方人工核对公钥；尚未做独立密码学审计。
- 交换中的内容只在客户端私有缓存及服务端有上限的内存中。客户端归档后确认收到，服务器立即清除中转密文；未完成会话超时清除，重启全部清除。
- 本机历史的两张图片、日期索引及自选背景均以 AES-256-GCM 加密保存在应用私有 `noBackupFilesDir/journal`，密钥位于 Android Keystore；不会参加云备份或设备迁移。主动另存到相册的图片是普通相册文件。
- 服务端业务数据目录只有 `signing.key` 与 `stats.json`，后者只有累计注册设备数。TLS 密钥、源代码、运行依赖属于服务基础设施。
- 服务禁用内容访问日志、进程核心转储和自身交换空间使用。不会创建相册、对象存储或记录数据库。
- 统计是注册设备次数，包含重装及测试注册，不等同真实人数。
- 配对证书放在手机，服务重启后双方重连可恢复。解除配对会通知在线好友，并在服务端内存中撤销证书；离线好友重连后也会清除旧配对。本机已解除配对时会拒绝对端恢复旧关系。无持久身份/撤销数据库，不提供服务端跨重启全局凭据撤销。
- 0.2.0 起新增完成的记录自动归档，退出、重启和覆盖升级后可继续读取。升级以前未归档的临时记录不会自动补录。清除应用数据或卸载会失去本机历史；对方保存的副本不会被远程删除。

## 本地构建

采用 Kotlin、Jetpack Compose Material 3、AGP 9.3.3、Gradle 9.5.0、JDK 17。开发环境位于 `C:\environment`，项目源码位于当前目录。

```powershell
py tools/prepare_android_env.py
powershell -ExecutionPolicy Bypass -File tools/build.ps1
```

首次部署需要先生成并取回 CA 公钥，否则 Android `@raw/sharetime_ca` 资源不存在，构建会失败。部署工具见下文。仓库中的 CA 仅适合本机这台服务器，其他部署需替换公钥后重新构建。测试 APK 使用开发签名，正式发布前需要配置独立发布签名并保持后续更新签名一致。

界面调用 `enableEdgeToEdge()`，通过 `Scaffold`、`safeDrawing`、`consumeWindowInsets` 与 `imePadding` 处理系统栏、安全区和输入法；不锁定方向，限制正文最大宽度，提供浅色/深色主题和可滚动布局。日历使用 Google Material 3 的 `DatePickerDialog` / `DatePicker`，日期按 UTC 日历值转换避免时区偏移；动画尊重系统关闭动画设置。

## 服务端部署与验证

部署工具通过 `cy` SSH 登录，再用 `su` 执行部署，密码交互输入，不写入源码。新版本位于独立 release 目录，`current` 链接切换；配置和计数保留。默认端口 8443，以专用低权限用户运行，必须保持单 worker。

```powershell
py tools/deploy.py
# 服务器下载受限时，使用适合 Debian x86_64/Python 3.13 的本地 wheel 目录：
py tools/deploy.py --wheels C:\environment\cache\sharetime-debian-wheels
C:\environment\sharetime-python\Scripts\python.exe -m pytest server -q
C:\environment\sharetime-python\Scripts\python.exe tools/smoke_server.py
```

`smoke_server.py` 会注册两个测试身份，使用合成图片验证 HTTPS/WSS 及双端加密闭环，会增加设备计数。不会使用个人照片。

Debian 管理命令（以 root 执行）：

```sh
systemctl status sharetime
systemctl restart sharetime
journalctl -u sharetime -n 30 --no-pager
```

## 测试范围

初版构建与服务端结果见 `docs/verification.md`；双机联调和 0.2.0 功能检查见 `docs/device-validation.md`。已覆盖联想 Android 11（USB ADB）和小米 Android 16（无线 ADB）；Android 8 与 Android 17 尚未进行对应版本真机测试。更长时间的待机、省电、横屏、分屏和大字体矩阵仍可按 `docs/device-test-plan.md` 补测。
