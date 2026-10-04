# LightNovelReader 独立开发与打包

我们在自己的 `Maker-Wen/LightNovelReader` 仓库中维护独立版本，当前长期开发分支为 `dev/independent-edition`。现有 AI 辅助开发内容留在本项目，不以合入上游为交付目标。上游贡献指南中的 PR 目标分支要求用于向上游贡献，不用于本项目的独立打包。

## 独立应用

| 项目 | 值 |
| --- | --- |
| 应用名 | LightNovelReader |
| applicationId | `io.github.makerwen.lightnovelreader` |
| 构建类型 | `independent` |
| versionCode | 以 `app/build.gradle.kts` 和 APK 的实际值为准；每次正式发布必须递增 |
| 版本名 | `1.3.0_MakerWen (构建日期)` |
| 更新方式 | 检查 `Maker-Wen/LightNovelReader` 的正式 GitHub Release；也可手动安装本项目 APK |

应用显示名沿用 LightNovelReader；独立 applicationId 使新应用与正式版、旧 Snapshot 的安装和数据区分开。使用现有导入导出功能迁移书架及设置；独立应用不自动读取、清空或搬迁旧应用数据。

保留原 Kotlin namespace 和插件 API/发现协议，避免因安装身份变更破坏插件 ABI。插件更新逻辑保持原有行为。现有 `lightnovelreader://` 链接继续支持，并存安装时 Android 可能让用户选择打开的应用。

## 作者书单

详情卡片和书籍信息弹窗中的作者名字可打开当前数据源的作者书单。文库 8 使用已有的作者搜索，碧蓝使用书籍详情提供的作者作品链接。查询保留原始作者名，仅去除首尾空白；界面中的简繁转换和格式处理只作用于显示文本。

作者入口通过可选的 `RelatedBooksDataSource` 能力声明，未声明能力的源保持作者文本显示。每次打开创建独立导航入口和页面会话，作者、标签及出版社书单共用展开页的结果收集、刷新、加载更多与空结果/错误显示。旧 `progressBookTagClick` 接口继续支持现有插件。

碧蓝通用列表优先读取站点实际提供的下一页链接，文库和排行榜保留页码规则作为后备；作者页的分页地址由页面链接决定。冷启动时按原书 ID 重新读取详情恢复作者链接，缺失或加载失败显示错误。多页作者书单通过 HTML 样本验证，实际站点作者样本为单页。

## 本地打包

在此分支的仓库根目录运行：

```sh
python3 scripts/build-independent.py --offline
```

首次缺少 Gradle 依赖缓存时去掉 `--offline`。脚本查找本地 JDK 21，使用现有 Android SDK，构建 `:app:assembleIndependent`，启用 R8 与资源压缩，并通过临时 Gradle init 脚本使用本机 Android debug keystore 签名。没有密钥写入仓库，也不会上传 APK。

产物存放在 `artifacts/apk/independent/<构建时间>/`，包含 APK、`update.json`、SHA-256、签名与清单检查、版本/分支状态和 R8 mapping。默认本地构建只允许 `dev/independent-edition` 分支；dirty 工作区也可构建，报告记录已跟踪差异及未跟踪文件的摘要，这种产物仅用于本地验收。

已有本地包使用本机的 `~/.android/debug.keystore` 签名。后续覆盖升级需要相同签名，应保留这份 keystore；多台机器分别生成的 debug keystore 不能直接互相覆盖升级。CI 必须使用与已安装本地包相同的固定 keystore 和 alias，才能覆盖升级；新建一份密钥或复用签名不同的上游 release 密钥都不能保持该升级关系。

脚本验证独立应用名、包名、provider authorities、非调试属性和独立更新开关。安装后的交互、插件和真机表现仍需单独验收。

## 已确认版本的构建

正式发布前先提交并确认版本，再使用确切 commit SHA 或 tag 构建：

```sh
git checkout <已确认的 commit SHA 或 tag>
python3 scripts/build-independent.py --release-ref <同一个 commit SHA 或 tag>
```

tag 也可使用 `--release-tag <tag>`，与 `--release-ref` 互斥。发布模式要求工作区 clean、`HEAD` 与指定 ref 解析出的 commit 严格一致，且该 commit 属于 `origin/dev/independent-edition` 或本地 `dev/independent-edition` 的历史；不满足条件会在构建前退出。默认本地命令仍受独立分支限制。

指定固定 keystore 时使用 `--keystore <path> --key-alias <alias>`，密码通过 `INDEPENDENT_STORE_PASSWORD` 和 `INDEPENDENT_KEY_PASSWORD` 环境变量提供。使用 `--expected-signing-certificate-sha256 <已确认的证书 SHA-256>` 对成品 APK 的 signer 做固定校验；CI 必须提供该值。

可用 `--build-tools-version <版本>` 固定 APK 检查使用的 SDK 工具版本。独立版 CI 固定命令行工具 22.0、平台 `android-37.0` 和检查工具 `36.0.0`，与本地验收的 SDK 配置一致。构建或检查失败时仍保留诊断产物；只有成功生成元数据并通过验收的 APK 可用于正式发布。

## 人工触发的独立版 CI

`.github/workflows/build-independent.yml` 仅提供 `workflow_dispatch`，需人工运行并填写必填的 `release_ref`（已确认的 commit SHA 或 tag）。workflow 完整 checkout 该 ref，并单独获取 `origin/dev/independent-edition` 历史，再准备 JDK 21、Python 和 Android SDK，调用同一构建脚本。

GitHub 要求手动 workflow 文件存在于仓库默认分支，才能提供 **Run workflow** 入口。目前仓库默认分支已设为 `dev/independent-edition`，该分支包含此 workflow，默认分支前置条件已满足，可到 Actions 人工运行。此构建流程不会修改远端默认分支。参见 [GitHub 手动运行 workflow 文档](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)。

构建使用以下固定 repository secrets：

| Secret | 用途 |
| --- | --- |
| `INDEPENDENT_SIGNING_KEY` | 与现有独立安装包一致的固定 keystore 文件的 base64 内容 |
| `INDEPENDENT_KEY_ALIAS` | 该 keystore 中使用的 key alias |
| `INDEPENDENT_STORE_PASSWORD` | 该 keystore 的密码 |
| `INDEPENDENT_KEY_PASSWORD` | 该 key 的密码 |
| `INDEPENDENT_SIGNING_CERTIFICATE_SHA256` | 与本机已安装包相同 signer 的固定证书 SHA-256，用于拒绝错误密钥 |

运行前必须确认这些 secrets 指向与本机安装包相同的签名材料。独立版使用专用 secrets，避免覆盖旧 release 流程的签名身份。keystore 只解码到 runner 临时目录，流程结束时删除，不进入仓库或上传的 artifacts。

workflow 只上传 `artifacts/apk/independent/` 的构建产物，不自动创建 GitHub Release。现有 `.github/workflows/marge.yml` 的 push 分支过滤已在 `dev/**`、`release/**` 之后增加 `!dev/independent-edition`，跳过独立分支；其他 dev/release 分支仍按原有文件路径过滤规则触发正常版 release 构建，它不提供独立版 APK。workflow 配置不等于已验证的 CI：需要实际完成一次人工运行，核对产物中的签名证书与已安装包一致，并保留成功构建记录，才能确认远端独立打包流程可用。

## 正式 Release 与更新元数据

首次正式 Release 必须先把 `versionCode` 增加到高于已安装本地包的值；后续每次发布也必须递增。将版本改动提交到独立分支，确认该历史中的确切 commit 或 tag，再以发布模式本地构建或人工触发独立版 CI。验收后，在 `Maker-Wen/LightNovelReader` 人工创建指向该 commit 的 draft Release，并上传同一次构建的 APK 和 `update.json`；核对文件名、字节大小、SHA-256 与签名后，再人工发布为正式 Release。仅上传 workflow artifact、创建 draft 或 prerelease 都不会成为客户端正式更新。

`update.json` 的 schemaVersion 为 `1`，字段如下：

| 字段 | 内容 |
| --- | --- |
| `schemaVersion` | 固定为 `1` |
| `applicationId` | APK 的实际包名，必须为 `io.github.makerwen.lightnovelreader` |
| `versionCode` | APK 的实际整数版本号 |
| `versionName` | APK 的实际版本名 |
| `apkFile` | 同次构建的 APK 文件名，必须与 Release 中的 APK asset 名称一致 |
| `sizeBytes` | APK 的实际字节数 |
| `sha256` | APK 字节内容的 SHA-256 |
| `minSdk` | APK 清单的实际 minSdk |
| `signingCertificateSha256` | `apksigner` 校验取得的实际 signer 证书 SHA-256 |

包名、版本和 minSdk 从实际 APK 的 `aapt` 输出读取，签名证书从 `apksigner` 输出取得，大小和文件 SHA-256 从 APK 字节计算。元数据不包含下载 URL 或发布页 URL；客户端从同一个正式 Release 按 `apkFile` 绑定 APK asset，使用其下载 URL，并使用该 Release 的 `html_url` 打开发布页。独立构建不检查或下载上游应用更新，插件更新仍保持现有行为。

元数据解析、拒绝错误包与发布历史检查可以独立运行：

```sh
python3 scripts/check-independent-metadata.py
```

这些回归检查使用合成报告和临时 Git 仓库，不构建、签名或验证 APK，也不生成可发布产物。真实 APK 的校验证据由构建脚本产生，CI 与设备表现仍需分别验证。
