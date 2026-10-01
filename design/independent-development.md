# LightNovelReader 独立开发与打包

我们在自己的 `Maker-Wen/LightNovelReader` 仓库中维护独立版本，当前长期开发分支为 `dev/independent-edition`。现有 AI 辅助开发内容留在本项目，不以合入上游为交付目标。上游贡献指南中的 PR 目标分支要求用于向上游贡献，不用于本项目的独立打包。

## 独立应用

| 项目 | 值 |
| --- | --- |
| 应用名 | LightNovelReader |
| applicationId | `io.github.makerwen.lightnovelreader` |
| 构建类型 | `independent` |
| 当前 versionCode | `10300010` |
| 版本名 | `1.3.0_MakerWen (构建日期)` |
| 更新方式 | 手动安装本项目 APK；独立构建不检查或下载上游应用更新 |

应用显示名沿用 LightNovelReader；独立 applicationId 使新应用与正式版、旧 Snapshot 的安装和数据区分开。使用现有导入导出功能迁移书架及设置；本次不会自动读取、清空或搬迁旧应用数据。

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

产物存放在 `artifacts/apk/independent/<构建时间>/`，包含 APK、SHA-256、签名与清单检查、版本/分支状态和 R8 mapping。dirty 工作区也可构建，报告记录已跟踪差异及未跟踪文件的摘要。后续覆盖升级需要相同签名，应保留本机的 `~/.android/debug.keystore`；多台机器分别生成的 debug keystore 不能直接互相覆盖升级。

脚本验证独立应用名、包名、provider authorities、非调试属性和独立更新开关。安装后的交互、插件和真机表现仍需单独验收。

## 远端构建边界

当前提供的是本地可重复构建流程。原有 GitHub APK workflows 仅针对 `master/refactoring`，不能视为此独立分支的 CI。此处没有改动或触发上游 Maven/Vercel 发布工作流，也没有配置自动发布或自动更新源。
