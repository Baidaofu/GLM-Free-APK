# GLM-Free-APK

将 [GLM-Free-API](https://github.com/izaart95-jpg/GLM-Free-API)（把 chat.z.ai 的 GLM 模型转换为 OpenAI / Anthropic 兼容 API 的 Go 反代）移植到 Android 的 APK 封装。**仅 arm64-v8a，Android 7.0+。**

## 原理

上游 Go 服务端交叉编译为 `android/arm64` 可执行文件，以 `libzaiapi.so` 之名打包进 `jniLibs`（借 Android 机制释放到有执行权限的原生库目录）；App 以子进程方式运行它（前台服务保活），实时捕获 stdout/stderr 显示在界面日志区。

本项目在移植过程中修复了三个平台适配问题（均已在源码中解决）：

1. **Android DNS** — 纯 Go 构建在 Android 上无 `/etc/resolv.conf`，注入显式 DNS 解析器（`internal/platform/resolver_android.go`，环境变量 `ZAI_DNS_SERVERS` 可覆盖）
2. **Aliyun ESA 国际节点强制 HTTP/2** — 部分节点对 Chrome TLS 指纹无视 ALPN 强制 h2；`dialUTLS` 改为通告真 Chrome ALPN（`h2, http/1.1`）+ `utlsConnAdapter` 暴露协商结果 + `ForceAttemptHTTP2`，两种协议都能正常工作
3. **captcha 风控 IP 一致性** — 阿里云验证码校验"验证请求 IP 与 device token 采集 IP 一致"；应用提供「出站代理」设置，使 Z.AI 上游与 captcha 验证都从同一出口走

## 网络环境要求（游客对话需非中国大陆 IP）

> ⚠️ **未登录（游客 / guest）状态下，对话与 device token 采集都要求设备使用中国大陆以外的出口 IP。**

Z.AI 对中国大陆 IP 的游客链路直接拒绝：登录/注册页不可用、`z_um.getToken()` 拿不到 token、captcha 风控也会拦截。
实测表现（均在境内 IP 下）：

| 环节 | 现象 |
|---|---|
| WebView 采集 token | 页面停在登录墙，`采集 N` 返回 0 个 token，提示「z_um SDK 未就绪 / 全部失败」 |
| 游客对话 | `/v1/chat/completions` 返回空响应、403 或频繁重试 captcha |
| 服务端日志 | `captcha` / `forbidden` / `empty response` 类错误刷屏 |

因此：

- **手机独立使用**：先开 SagerNet / Clash 等代理，连到境外或香港 / 日本 / 新加坡等节点；采集 token 与调用 API 必须**用同一个节点**（阿里云 captcha 校验出口 IP 与 device token 采集 IP 一致），并把该节点的本地端口填入 App 的「出站代理」。
- **USB + PC 代理**：PC 起本地代理 + `adb reverse tcp:2080 tcp:2080`，App 出站代理填 `http://127.0.0.1:2080`。
- 若无法使用境外节点，可在设置里填 `ZAI_TOKEN`（已登录账号）走登录通道，但**未登录游客模式在境内 IP 下不可用**。

## 界面功能

- **启动服务 / 停止服务** —— 前台服务保活，子进程运行 Go 服务端（默认端口 `3001`）
- **导入 token 库** —— 选择 PC 端 `token-collector` 采集的 `tokens.sqlite`（带 SQLite 校验 + 旧库自动备份）
- **Token 余量显示** —— 轮询 `/health` 的 `tokenCount`，余量不足时变红提醒
- **设置** —— 端口 / API Key（AUTH_TOKEN）/ 出站代理（HTTPS_PROXY）/ ZAI_TOKEN（可选，解锁全模型与图片输入）/ AgentMode 开关
- **实时运行日志** —— Go 服务端全部输出；支持一键复制、长按自由选择

### UI：Material 3 Expressive

界面基于 [Material Components 1.14](https://maven.google.com/web/index.html#com.google.android.material:material) 的
**Material 3 Expressive** 主题与组件重构（XML 布局 + ViewBinding，无手写 setBackgroundColor）：

- 主题 `Theme.ZaiApi`（父主题 `Theme.Material3Expressive.DayNight.NoActionBar`），品牌色为与启动图标一致的蓝紫渐变；
  `values-night/` 提供暗色方案，状态栏/导航栏图标亮暗自动切换
- 设置里的 **「跟随系统动态取色（Android 12+）」** 可切换到 `Theme.Material3Expressive.DynamicColors`，取壁纸配色
- 组件：`MaterialToolbar` + `AppBarLayout(liftOnScroll)`、`MaterialCardView`(filled/outlined，Expressive 大圆角)、
  `MaterialButton`(filled/tonal/outlined/text)、`TextInputLayout`(OutlinedBox + 密码切换)、`MaterialSwitch`、
  `Chip`(Token 余量)、`MaterialDivider`、`LinearProgressIndicator`、`MaterialAlertDialogBuilder`、`Snackbar`
- 边到边布局：`targetSdk 35` 下 Android 15 强制 edge-to-edge，根布局按 systemBars/IME inset 自动补内边距
- 图标：Material Symbols（Apache-2.0）矢量图，集中在 `res/drawable/ic_*.xml`

## 使用

1. 安装 APK，点击「导入token库」选择 PC 端采集好的 `tokens.sqlite`。
2. **配置出站代理**（关键）：captcha 风控要求出站 IP 与 token 采集环境一致：
   - USB 连接 PC 时：PC 起本地代理 + `adb reverse tcp:2080 tcp:2080`，APK 代理填 `http://127.0.0.1:2080`
   - 手机独立使用：先在手机上开 SagerNet/Clash 连接与采集时**相同的节点**，APK 代理填其本地端口（如 `http://127.0.0.1:7890`）
3. 点击「启动服务」，等待日志出现 `服务就绪 ✓`。
4. 客户端以 `http://127.0.0.1:3001/v1` 为 Base URL，API Key 与设置一致（默认 `Waguri`）。

### 可用模型

| 模型 ID | 说明 |
|---|---|
| `glm-4.7` | guest 会话可用 |
| `x-preview-l` | GLM-5.3-Flash 的新 ID（上游改名），guest 可用 |
| 其余 GLM 模型 | 需在设置中填入 `ZAI_TOKEN`（登录 chat.z.ai 后从 localStorage 取 `token`） |

> ⚠️ `glm-5.3-flash` 旧 ID 已在上游失效，会导致空响应。
>
> ⚠️ 游客（未登录）模式下 `glm-4.7` / `x-preview-l` 也仅在**非中国大陆 IP** 下可用，详见上文「网络环境要求」。

## 构建

环境：JDK 17、Android SDK（platform 35 + build-tools 35.0.0）、Go ≥ 1.27。

依赖：仅 `com.google.android.material:material:1.14.0`（传递引入 AndroidX，需要 `android.useAndroidX=true`，见 `gradle.properties`）。

```bash
# 1. 从 go-server/ 源码构建 Android 原生服务端
cd go-server
CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
  go build -trimpath -ldflags="-s -w" \
  -o ../app/src/main/jniLibs/arm64-v8a/libzaiapi.so .
cd ..

# 2. 构建 APK
./gradlew assembleRelease    # 产物: app/build/outputs/apk/release/app-release.apk
```

仓库已内置示例签名 `app/keystore/zaiapi.keystore`（口令 `ds2api123`，仅方便构建可安装的 APK），正式发布请自行更换。

## GitHub Actions

推送到 `main` 或手动触发 workflow 即可自动构建：Go 交叉编译 → Gradle 打包 → 上传 APK artifact（`GLM-Free-APK-arm64`）。

## 版本对应

- Go 服务端：[izaart95-jpg/GLM-Free-API](https://github.com/izaart95-jpg/GLM-Free-API) main（含 DNS / HTTP-2 / captcha-proxy 平台补丁，见 `go-server/`）
- Android 壳：工程结构参考 [SUN-0v/ds2api-android](https://github.com/SUN-0v/ds2api-android)

## 许可与免责

- `go-server/` 保留上游 GLM-Free-API 的 MIT 许可；Android 壳工程结构参考自 AGPL-3.0 的 ds2api-android，本仓库整体以 AGPL-3.0 发布。
- 本项目仅供学习研究，请遵守 Z.AI 服务条款，使用内置 token 时请自行评估合规性。
