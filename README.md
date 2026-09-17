# GLM-Free-APK

将 [GLM-Free-API](https://github.com/izaart95-jpg/GLM-Free-API)（把 chat.z.ai 的 GLM 模型转换为 OpenAI / Anthropic 兼容 API 的 Go 反代）移植到 Android 的 APK 封装。**仅 arm64-v8a，Android 7.0+。**

## 原理

上游 Go 服务端交叉编译为 `android/arm64` 可执行文件，以 `libzaiapi.so` 之名打包进 `jniLibs`（借 Android 机制释放到有执行权限的原生库目录）；App 以子进程方式运行它（前台服务保活），实时捕获 stdout/stderr 显示在界面日志区。

本项目在移植过程中修复了三个平台适配问题（均已在源码中解决）：

1. **Android DNS** — 纯 Go 构建在 Android 上无 `/etc/resolv.conf`，注入显式 DNS 解析器（`internal/platform/resolver_android.go`，环境变量 `ZAI_DNS_SERVERS` 可覆盖）
2. **Aliyun ESA 国际节点强制 HTTP/2** — 部分节点对 Chrome TLS 指纹无视 ALPN 强制 h2；`dialUTLS` 改为通告真 Chrome ALPN（`h2, http/1.1`）+ `utlsConnAdapter` 暴露协商结果 + `ForceAttemptHTTP2`，两种协议都能正常工作
3. **captcha 风控 IP 一致性** — 阿里云验证码校验"验证请求 IP 与 device token 采集 IP 一致"；应用提供「出站代理」设置，使 Z.AI 上游与 captcha 验证都从同一出口走

## 界面功能

- **启动服务 / 停止服务** —— 前台服务保活，子进程运行 Go 服务端（默认端口 `3001`）
- **导入 token 库** —— 选择 PC 端 `token-collector` 采集的 `tokens.sqlite`（带 SQLite 校验 + 旧库自动备份）
- **Token 余量显示** —— 轮询 `/health` 的 `tokenCount`，余量不足时变红提醒
- **设置** —— 端口 / API Key（AUTH_TOKEN）/ 出站代理（HTTPS_PROXY）/ ZAI_TOKEN（可选，解锁全模型与图片输入）/ AgentMode 开关
- **实时运行日志** —— Go 服务端全部输出；支持一键复制、长按自由选择

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

## 构建

环境：JDK 17、Android SDK（platform 35 + build-tools 35.0.0）、Go ≥ 1.27。

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
