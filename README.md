**简体中文** | 上游多语言介绍：[繁體中文](README_TW.md) · [English](README_US.md) · [Русский](README_RU.md)

<div align="center">
    <h1>LightNovelReader</h1>
    <a><img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-0095D5.svg?logo=kotlin&logoColor=white&style=for-the-badge"/></a>
    <a><img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white&style=for-the-badge"/></a>
    <p>Android 轻小说阅读器，使用 Kotlin 与 Jetpack Compose 编写</p>
    <img src="assets/header.png" alt="LightNovelReader" width="80%"/>
</div>

## 介绍

本仓库由 Maker-Wen 维护，在 [上游 LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) 的基础上继续开发，重点改进阅读导航、书源浏览、导出备份和应用更新。支持 Android 7.0 及以上。

## 本分支新增与改进

| 方向 | 具体内容 |
| --- | --- |
| 全书进度导航 | 新增阅读底栏和全书进度条。拖动时预览目标章节与位置，松手后跳转到章内；菜单展开期间可通过临时原位置点返回跳转前的位置。 |
| 阅读位置恢复 | 统一翻页与滚动模式的定位流程，改善模式切换、调整排版、快速切章及重新进入阅读时的位置恢复；定位完成后再保存阅读记录。 |
| 正文加载 | 同章跳转复用已加载正文与排版；翻页模式在章首或已解析的历史位置可提前开始阅读。完善加载容错，刷新失败时保留已有可读内容。 |
| 内置碧蓝书源 | 新增碧蓝（Linovelib）内置书源，无需另装碧蓝插件。支持推荐、文库筛选、排行、完结列表、搜索、分卷目录、章节分页合并与插图缓存，保留原有文库 8 书源。 |
| 作者作品书单 | 在详情卡片或书籍信息中点击作者，查看当前书源的作品。支持文库 8、碧蓝作者书单，提供刷新、分页结果展示及空结果、错误提示。 |
| EPUB 导出 | 修复段落正文、特殊字符、插图选项和分卷资源处理，避免多卷导出提前清理共享图片；文件写入失败会明确报告。 |
| 数据备份与恢复 | 完善 `.lnr` 备份：按内容选项导出各书源数据，合并书架、目录、阅读记录与进度时去重，避免重复累加统计。覆盖导入前校验文件，导入与书源切换支持中断恢复。 |
| 本仓库发行与更新 | 使用本仓库专属包名，可与上游版本并存。设置中手动检查本仓库的正式 Release，发现更新后打开发布页下载 APK；固定签名支持后续覆盖升级。 |

## 基础功能

沿用上游的书架与收藏、书本更新提示、离线优先缓存、翻页与滚动阅读、关键词搜索和分类探索，以及多书源、插件与 EPUB 导出能力。

## 下载与更新

- **正式版本**：从 [GitHub Releases](https://github.com/Maker-Wen/LightNovelReader/releases/latest) 下载 `LightNovelReader-<版本>.apk`。
- **开发构建**：在 [Actions](https://github.com/Maker-Wen/LightNovelReader/actions/workflows/build-independent.yml) 中选择已成功完成的构建，从 Artifacts 下载并解压取得 APK。
- **应用内检查**：进入设置的更新区域，点击“检查更新”，发现新版本后点击“打开发布页”，通过 Android 系统安装器覆盖安装。

本仓库安装包的包名为 `io.github.makerwen.lightnovelreader`。与上游应用并存时，两个应用的数据分别保存；可使用 `.lnr` 导出、导入书架和设置。同包名、同签名的后续版本可直接覆盖升级。

## 插件与自定义书源

可通过插件添加自定义书源，相关上游资源：

- [示例插件](https://github.com/dmzz-yyhyy/LightNovelReaderPlguin-Template)
- [开发指南](https://lnr.nariko.org/plugin-dev/)
- [LNR API KDoc](https://api-doc.lnr.nariko.org/)

## 开发与贡献

当前开发分支为 `dev/independent-edition`。本地构建需要 JDK 21 和 Android SDK，在仓库根目录运行：

```sh
python3 scripts/build-independent.py
```

构建产物位于 `artifacts/apk/independent/<构建时间>/`。构建、签名和 CI 发布流程见 [开发与打包说明](design/independent-development.md)；EPUB 模块说明见 [EpubLib](epub.md)。

欢迎通过 [Issues](https://github.com/Maker-Wen/LightNovelReader/issues) 反馈问题或提出建议。向本仓库提交 Pull Request 时，请以 `dev/independent-edition` 为目标分支，并说明改动与验证方式。

## 软件截图

以下截图沿用上游项目：

| |
| --- |
| ![界面截图 1](assets/light1.png) |
| ![界面截图 2](assets/light2.png) |
| ![界面截图 3](assets/light3.png) |

## 上游与致谢

感谢 [原项目](https://github.com/dmzz-yyhyy/LightNovelReader) 的开发者、贡献者与翻译者。原项目相关资源：

- 社区：[QQ](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=P__gXIArh5UDBsEq7ttd4WhIYnNh3y1t&authKey=GAsRKEZ%2FwHpzRv19hNJsDnknOc86lYzNIHMPy2Jxt3S3U8f90qestOd760IAj%2F3l&noverify=0&group_code=867785526) · [Discord](https://discord.gg/pnf4ABmDJt) · [Telegram](https://t.me/lightnoble)
- 翻译：[Crowdin](https://crowdin.com/project/lightnovelreader)
- 支持原作者：[爱发电](https://www.ifdian.net/a/lightnovelreader)
- 上游版本分发：[F-Droid](https://f-droid.org/packages/indi.dmzz_yyhyy.lightnovelreader)

## License

```
Copyright (C) 2024 by NightFish <hk198580666@outlook.com>
Copyright (C) 2024 by yukonisen <yukonisen@curiousers.org>

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```
