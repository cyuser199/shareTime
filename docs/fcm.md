# FCM 与自动接收渠道

0.8.1 增加 Firebase Cloud Messaging，Android 8 起支持。Firebase 项目为 `sharetime-38da9`，Android 包名为 `cn.sharetime.app`。

## 自动选择

- 用户接受隐私说明 v8、开启“保持接收邀请”后，检查 Google Play 服务，再获取 FCM 令牌，并向当前服务器进行已认证登记。
- Google 服务、Firebase 配置、令牌或服务端登记不可用时，使用原有后台长连接；设置显示当前判断结果。不会自动安装 Google 服务。
- FCM 可用时辅助唤醒和显示提醒，长连接仍负责加密记录同步与兜底。因此开启持续接收仍有静默前台服务通知。
- 国内版小米若没有可用 Google 服务，将使用长连接。当前没有集成小米推送 SDK，不能把长连接描述为小米系统推送。
- 强制停止、关机、厂商清理、Google 服务或网络不可达仍可能导致延迟；拿到令牌及服务端配置正常也不等于保证送达。

## 配置与部署

Android 配置文件放在 `android/app/google-services.json`，已加入 Git 忽略规则。只引入 Firebase Messaging，不引入 Analytics。缺少配置文件的自建版本保留长连接功能。文件必须对应 Android 应用包名。

服务端使用 FCM HTTP v1，通过 `SHARETIME_FCM_CREDENTIALS` 指向服务账号 JSON。云端路径为 `/opt/sharetime/secrets/firebase-service-account.json`，目录权限 750，文件 root:sharetime、640。私钥不能打包进 APK、写入日志或提交 Git。服务账号应只授予发送 FCM 消息所需权限。

`SHARETIME_FCM_ENABLED=0` 可暂停 FCM 登记激活与发送，让客户端明确回退长连接；用于服务器尚无 Google 网络出口等情况。恢复前先执行 validate-only 请求验证凭据与 API 连通性。

`POST /push/device` 使用现有设备凭据的 Bearer 身份认证；登记需 `provider=fcm`、`pushToken`、`mode` 和 `privacyVersion>=8`。关闭时使用 `provider=none`。令牌与设备、当前模式对应关系保存在服务端私有 `push-devices.json`；30 天不刷新后清理，Google 报告 UNREGISTERED 后删除。一个令牌仅有一个设备身份。更换身份、关闭后台接收会尝试取消登记；断网期间取消可能失败，本机忽略后续消息。

推送只含事件类型、模式和不透明去重标记，没有图片、昵称、配对码、公钥或解密密钥。真实图片仍走原有端到端加密协议。邀请及已提交成员可获得的记录更新触发推送；队列与去重表有界，Google 请求在独立线程执行，不阻塞 WebSocket 心跳和记录锁。重复 FCM 事件在本机去重，通知使用同一个邀请通知位置。

隐私说明 v8 增加 Google 推送处理范围、令牌登记和取消规则，必须由使用者本人选择同意。

## 验证范围

服务端测试覆盖鉴权、同意版本、令牌唯一归属、重启读取、取消登记、过期清理、模式隔离、重复入队、无效令牌清理以及发送失败隔离。FCM validate-only 可以检查服务器凭据、权限、API 和网络，不代表设备已收到消息。

实际投递测试需要已接受 v8 的 Google 服务设备。初次检测时三台测试模拟器均未安装 Google Play 服务；续测时 Sim-B 已具备 Google 服务，应用实际显示“后台长连接 · 服务器未启用 FCM”，服务器连接正常。没有修改网络设置、停用 Google 服务或清除应用数据来制造测试条件。

2026-11-01：43 项服务端测试通过；云服务器连接 Google OAuth 和 FCM 接口超时，使用者确认暂时没有可用出口，稍后再配置。目前云端 `/push/config` 明确返回 `enabled=false`、`provider=connection`，FCM 实际发送与设备收件尚未验证。

Android assembleDebug、11 项单元测试、lint（0 错误）通过；APK 签名及 16 KiB 对齐检查通过，覆盖安装至 Sim-B，原身份保留。客户端源码与 D 盘构建副本逐文件一致。0.8.1 APK SHA-256：`3a1fbc779bb4755dedaceed93137f2246b8b995c848559c7d4555b3642d00e44`。本次未发布公开 APK 更新，正式更新接口仍为 0.8.0。

官方参考：[Android 接入](https://firebase.google.com/docs/cloud-messaging/android/get-started)、[HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api)、[消息优先级](https://firebase.google.com/docs/cloud-messaging/android-message-priority)、[隐私](https://firebase.google.com/support/privacy)。
