# 验证记录 · 2026-10-29

本文件保留初版构建和服务端验证。后续双机实测、0.1.1 修复与 0.2.0 时间轴/日历/背景功能见 [真机验证记录](device-validation.md)。

交付版本：此刻 0.1.0 测试版。客户端最低 Android 8.0/API 26，编译及目标 Android 17/API 37。

## 已完成的验证

| 检查 | 结果与证据 |
|---|---|
| Android 构建 | `assembleDebug testDebugUnitTest lintDebug` 成功；见 `android-build-final.log` |
| Android 加密单元测试 | 2 项通过，0 失败；覆盖双端解密、随机 nonce、安全码对称性、篡改和跨轮重放认证失败 |
| Android Lint | 0 错误，2 条依赖新版提示（OkHttp、Coroutines），未屏蔽检查；见 `../android/app/build/reports/lint-results-debug.html` |
| APK 元数据 | `cn.sharetime.app`，versionCode 1，versionName 0.1.0，minSdk 26，targetSdk 37 |
| APK 签名 | `apksigner verify --print-certs` 成功，为测试用途 Android Debug 签名 |
| 新设备页面对齐 | `zipalign -c -P 16 4` 成功；四种 ABI 的 ELF LOAD 段均为 16384 字节对齐 |
| 服务端自动测试 | 7 项通过；配对确认、同意前禁止提交、双方提交后展示、拒绝/超时、凭据篡改、越权操作、重启恢复、同时邀请、重复提交、离线配对撤销 |
| Debian 实际部署 | `sharetime.service` 为 active/running，非 root 专用用户运行；当前版本目录 `/opt/sharetime/releases/20261029-165716` |
| 实际双客户端闭环 | 从 Windows 使用真实 HTTPS/WSS 连接 Debian：证书校验、配对、邀请同意、双方加密提交、解密校验合成图片一致、回执及解除配对全部通过 |
| 内容留存检查 | 服务业务数据目录只有 `stats.json`（25 字节）和 `signing.key`（32 字节），没有图片文件或记录数据库 |
| 内存保护 | systemd 实测 `MemorySwapMax=0`、`LimitCORE=0`；访问日志关闭，服务日志级别 warning |
| 交付前服务状态 | `/health` 返回 `{"status":"ok","protocol":1}`；测试设备计数已清零，`/stats` 返回注册 0、在线 0 |

APK：`../deliverables/ShareTime-0.1.0-debug.apk`

SHA-256：

```text
4290d512225df2a82161696a062e74c254197a15e2dd54159cf9c0c70aafad88
```

## 尚未验证

本初版验证阶段尚未连接真机；后续已接入 Android 11、Android 16 两台手机，结果见上方真机验证记录。Android 17、Android 8、长期省电待机与系统回收矩阵仍需要按 `device-test-plan.md` 补测。

当前仅配置局域网服务器。外网地址、VPN/公网入口、厂商离线推送、应用商店发布及正式发布签名尚未配置。客户端没有云端历史相册。

本记录区分构建/静态检查、模拟客户端联网验证和真机验证，不将前两者等同于真机体验通过。
