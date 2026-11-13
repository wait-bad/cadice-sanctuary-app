# 📜 Violet IM (`cadice-sanctuary-app`) 版本演进与架构改动记录

本文档完整记录了 `cadice-sanctuary-app`（VoceChat 私有云 Android 原生客户端）从 `v1.5.0` 到最新 `v1.7.2` 的核心缺陷修复、架构重构与新特性清单，便于随时回溯每一项代码变更与设计原因。

---

## ⚡ v1.7.2（Build 20 · 1秒极速重连 + QQ风格搜索栏 + 三页花瓣下拉刷新 + 永久固定签名）

### 🔌 1. 秒级断网重连自愈 + 智能省电 (`VioletApp.kt` / `SanctuaryCore.kt`)
- **适配 VoceChat 5s/15s 心跳节奏**：将 SSE `readTimeout` 从 `0`（无限死等）收紧为 `22 秒`，假死看门狗阈值从 `38s` 压缩至 `20s`；
- **Wi-Fi ↔ 5G 切换秒换线**：`NetworkCallback` 记录 `networkHandle`，只要底层网络切换即刻 `evictAll()` + 取消旧流并唤醒新连；
- **前后台智能生命周期管家 (`VioletApp`)**：未开启后台保活时切后台自动优雅休眠（接近 0 耗电），切回前台瞬间调用 `pokeOrRestartSse` 秒速拉起并缩短退避上限至 `6 秒`（消除原本长达 1~2 分钟的重连卡顿）。

### 🌸 2. QQ 风格圆角搜索栏 & 跨三页「小花」下拉刷新 (`FlowerPullRefreshLayout.kt` / `HomeActivity.kt`)
- **QQ 风格消息顶部搜索栏**：移除顶栏独立的搜索与刷新文字按钮，在消息页最上方植入圆角搜索胶囊与极简矢量搜索图标 (`ic_search.xml`)；
- **三页通用花瓣下拉刷新 (`FlowerPullRefreshLayout`)**：在「消息」「联系人」「空间」三个页面下拉时与顶栏拉出平滑阻尼间距并出现**旋转五瓣小花**：
  - 刷新成功：小花瞬间**分解成 7 片零散花瓣**优雅飘落渐隐后回弹收起；
  - 刷新失败：小花**持续旋转**提醒（点击小花或再拉可重试，上滑自动收起）。

### 🔐 3. 永久固定签名指纹（告别反复卸载！）(`app/build.gradle` / `.github/workflows/build.yml`)
- 生成 100 年有效期专用签名密钥库 `violet_im_signing.jks`（SHA256: `7F:3D:5E:5F:F9:18:42:87:2B:ED:84:C4:87:92:BC:8C:9A:97:61:32:B6:26:3E:81:1A:30:8A:24:3E:23:F3:B5`）并通过 GitHub Secrets 持久化注入 CI；
- 今后每次新版本 APK 均保持完全一致的签名指纹，**手机端直接覆盖安装升级即可，聊天记录和登录状态全部保留**！

---

## 🚀 v1.7.1（Build 18 · 紧急修复发消息失败故障）

### 🐛 核心故障根因定位与修复
1. **彻底根治“发不出消息 / 引用回复报错”故障 (`HTTP 415 Unsupported Media Type`)**
   - **根因**：VoceChat 后端的 `/api/group/{gid}/send`、`/api/user/{uid}/send`、`/api/message/{mid}/reply` 以及图片消息发送接口严格校验请求头 `Content-Type` 必须精确为 `text/plain` 或 `vocechat/file`；而 OkHttp 的 `String.toRequestBody("text/plain".toMediaType())` 会在底层**自动追加 `; charset=utf-8`**（即变成 `text/plain; charset=utf-8`），导致 VoceChat 服务端直接返回 `HTTP 415`。
   - **修复 (`SanctuaryCore.kt`)**：
     - 将所有发送文本、引用回复与图片消息的请求体统一改为 **`ByteArray.toRequestBody(...)`**（`text.toByteArray(Charsets.UTF_8).toRequestBody("text/plain".toMediaType())` 与 `payloadBytes.toRequestBody("vocechat/file".toMediaType())`），彻底禁止 OkHttp 自动改写 `Content-Type`；
     - 新增 `SendResult(success, mid, filePath, error)` 结构化返回结果，发送失败时在 `ChatActivity` 弹窗透出具体 HTTP 状态码或异常详情，方便一眼定位问题。
2. **局域网/代理环境网络状态校验优化 (`SanctuaryCore.kt`)**
   - `ensureNetworkMonitor` 改用 `NET_CAPABILITY_INTERNET` 判定联网能力并增加构造期即时检查，避免内网直连（无公网 DNS 验证 `NET_CAPABILITY_VALIDATED` 时）误判为断网导致 SSE 长时间挂起。

---

## ✨ v1.7.0（Build 17 · 集 `GetStream` + `Element Android` 两大开源架构之长）

> 架构借鉴来源：`GetStream/stream-chat-android` 与 `element-hq/element-android`（已根据需求明确剔除「连发气泡合并」与「PIN 应用锁」）。

### 🐛 Bug 修复
- **修复「消息页头像右下角在线小绿点丢失」问题 (`Adapters.kt` / `SanctuaryCore.kt` / `HomeActivity.kt`)**
  - **根因**：`HomeActivity.loadData()` 并发拉取各会话最新摘要 (`hydrateLatestPreviews`) 耗时期间，SSE 推来的 `users_state` 点亮了在线状态；但 `hydrateLatestPreviews` 返回后拿着旧列表快照整表覆盖了 `conversationList`，把在线状态洗回 `false`。
  - **修复**：`ConversationAdapter.onBindViewHolder` 直接以 `SanctuaryCore.isUserOnline(item.id)` 作为唯一权威真相源，并在 `fetchLatestPreview` 与 `reconcileUnreadWithLocalCursor` 中实时刷新在线状态，确保消息页与联系人页小绿点永远同步。

### 🌐 网络、事件流与发送保序层 (`SanctuaryCore.kt`)
- **网络状态监听 (`ensureNetworkMonitor`)**：注册 `ConnectivityManager.NetworkCallback`，断网/切网恢复瞬间通过 `sseWakeupChannel` 立即唤醒 SSE 重连，首页顶部副标题同步展示 `🟢 在线` / `🟠 网络重连中`。
- **SSE 假死心跳看门狗**：在 SSE 长连接读取循环上挂载 38 秒无包检测看门狗，隧道静默假死时主动中断并触发重连。
- **自适应微批收集通道 (`startMicroBatchCollector`)**：借鉴 `GetStream SocketEventCollector`，对高频实时消息采用 `300ms 静止窗口 / 1000ms 上限 / 200 条上限` 微批收集，防止群聊刷屏卡死 UI。
- **同会话严格串行发送 (`sendSequencers`)**：借鉴 `Element SemaphoreCoroutineSequencer`，按会话 Key (`g_{gid}` / `u_{uid}`) 分配 `Mutex`，保证同一聊天室内连发消息/图片严格按顺序到达服务器。

### 💬 聊天交互体验升级 (`ChatActivity.kt` / `Adapters.kt` / `SearchActivity.kt` / `Models.kt`)
- **日期分割条 + `ListAdapter` / `DiffUtil` 局部刷新**：引入 `sealed class ChatListRow`（`DateDivider` + `MessageRow`），自动按天插入 `今天` / `昨天` / `M月d日` 分割条，列表更新不闪头像。
- **右滑引用回复 (`SwipeReplyCallback`)**：接入 `ItemTouchHelper` 右滑手势（最大下拉 72dp、72% 阈值震动触发），底部弹出引用预览条，发送后在左右气泡内展示引用卡片（对接 VoceChat `POST /api/message/{mid}/reply`）。
- **智能滚底悬浮球 (`card_scroll_bottom`)**：上翻阅读历史消息时不被新消息强行扯回底部；离开底部且有新消息时显示 `↓ 回到底部` / `↓ N条新消息` 悬浮胶囊。
- **全局离线聊天记录搜索 (`SearchActivity.kt`)**：在 `SanctuaryDb` 恢复并升级 `searchMessages(...)`，首页右上角新增 `🔍` 按钮，支持毫秒级检索本地全库历史消息并直达对应会话。

### 🖼️ 富媒体处理、大图查看与系统外部分享 (`MediaHelper.kt` / `ImageViewerActivity.kt` / `ShareReceiverActivity.kt`)
- **图片扶正与隐私脱敏 (`MediaHelper.compressAndStripExif`)**：发送图片前先用 `inJustDecodeBounds` 降采样防 OOM，读取 `ExifInterface` 旋转角用 `Matrix` 扶正竖拍照片，再重新编码为纯净 JPEG，彻底抹除 GPS 经纬度与设备型号隐私信息。
- **VoceChat 图片上传与渲染**：对接 `/api/resource/file/prepare` + `/api/resource/file/upload`（`vocechat/file`）与 `/api/resource/file?file_path=...`，气泡内直接渲染圆角缩略图并做内存/磁盘双层缓存。
- **全屏大图浏览 (`ImageViewerActivity`)**：点击聊天图片进入黑底全屏查看器，支持双指缩放与单指拖拽。
- **系统外部分享接收 (`ShareReceiverActivity`)**：在 `AndroidManifest.xml` 注册 `ACTION_SEND`（`text/plain`、`image/*`），支持从相册/微信/浏览器点“分享”直接选 Violet IM 会话发送图文。

### 🔔 无 FCM 后台保活与通知栏系统 (`SanctuaryKeepAliveService.kt` / `NotificationHelper.kt` / `NotificationActionReceiver.kt` / `SettingsActivity.kt`)
- **前台长连接保活服务 (`SanctuaryKeepAliveService`)**：`FOREGROUND_SERVICE_TYPE_DATA_SYNC` 轻量级常驻服务，可在「设置 -> 无 FCM 实时后台保活」中自由开关。
- **防轰炸 `MessagingStyle` 通知 (`NotificationHelper`)**：
  - 独立低优先级后台线程 + **200ms 尾节流合并** + 最近 40 条 `mid` 环形去重；
  - 支持通知栏 `RemoteInput` **直接打字回复**与**一键标为已读**；
  - 进入某个 `ChatActivity` 时自动调用 `setCurrentRoom(...)` 屏蔽当前会话提醒并清空通知栏里该会话的旧通知。

---

## 💾 v1.6.0（Build 13 · 离线持久化与状态机基础）
- 新增账号隔离永久 SQLite 数据库 `SanctuaryDb.kt`（`messages` / `conversations` / `drafts` 三表）；
- 接入乐观发送状态机（`STATUS_SENDING=0` / `STATUS_SENT=1` / `STATUS_FAILED=2`）、失败点击重发、SSE/HTTP 竞态原子升格 (`promotePendingMessage`)；
- 接入草稿防抖落盘与列表 `[草稿]` 珊瑚红高亮、已读游标持久化 (`/api/user/read-index`)、增量 SSE (`&after_mid=`) 与历史双向分页、服务器删消息 (`DELETE /api/message/{mid}`)；
- 修复右侧聊天气泡布局边界裁切问题（`0dp` + `layout_weight="1"`）。

---

## 🎨 v1.5.0（Build 12 · UI 改版与隧道防爆）
- 极简矢量登录框图标、圆角输入与键盘避让布局、明暗双主题弹窗；
- QQ 风格左侧滑抽屉 (`DrawerLayout`)、全局明暗切换、全屏 `SettingsActivity`（相册头像上传、全局字号滑块）；
- VoceChat 5 分钟 Token 自动续期 (`/api/token/renew`) 与 `OkHttp` 并发节流 (`maxRequestsPerHost = 4`)。
