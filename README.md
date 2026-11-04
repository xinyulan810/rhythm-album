# 音游截图相册 · Rhythm Album

基于 **Aves** 改造的 Android 音游截图相册，使用 Flutter 与 Kotlin 开发。本项目是独立实验性衍生项目，不是 Aves 官方版本。

## 功能

- 自动收纳文件名含 `screenshot` 的截图，也支持手动导入。
- 按游戏分类，按相册内最新截图倒序排列；四宫格封面、实时筛选的相册命名下拉框。
- 中英日离线 OCR、文字搜索、AP（含 Pure Memory）与 FC 标签识别。
- 全屏滑动浏览、缩放、下滑返回，多选、合并、收藏、分享、批量导出和回收站。
- 全分辨率高质量有损编码：速度优先使用 4:4:4 JPEG，体积优先使用实验性 AV1 方案。
- 可选“代管截图”：首次启动选择，设置中可关闭。校验源文件与压缩归档后，通过 Android 系统确认删除原图。
- BanG Dream! 抽卡图使用独立内部类别，显示在同一个 BanG Dream! 相册。

## 当前状态与重要提醒

当前源码版本为 **0.4.0+13，实验性版本**。不要把本应用作为重要图片的唯一存储位置。

**压缩不是无损存档。代管删除原图后，清空应用数据或卸载会丢失尚未导出的图片。请先备份，再测试。**

当前已知限制：

1. 首次大量导入按导入、分类、压缩、OCR 的优先顺序处理。已分类但未压缩的图片暂时也会显示在“其他”，这是已知显示逻辑问题，不代表全部识别失败。
2. 开启代管不会自动删除原图：部分图片处理完成后即可点击“清理原图”，逐张验证并显示进度，当前批次最多 1000 张统一确认一次系统弹窗。要求 Android 11+、全部照片权限、压缩成功及 OCR 完成。
3. 当前代管仅处理 MediaStore 中符合 screenshot 命名的图片。手动文档选择器来源、压缩无收益的原样收纳图片不参与清理。
4. 分类与成绩标签可能误判；开发集回放结果不能代表所有游戏和截图的识别率。部分新分类规则尚未完成发行包真机回归。
5. 主要在单台 ARM64 Android 设备上测试；不承诺跨设备稳定 60fps。系统删除/取消和删除后浏览、导出流程仍在实测中。

源码公开不代表这些问题已经修复。反馈时请说明版本、系统版本、复现步骤，避免上传含私人信息的截图或日志。

## 构建

建议使用 Windows PowerShell。需要 Git、Python、JDK 21、Android SDK/NDK/CMake，以及相应原生构建工具。Flutter 版本由 `.flutter` 子模块锁定。

```powershell
git clone --recurse-submodules https://github.com/xinyulan810/rhythm-album.git
cd rhythm-album

# 改成你的 Android SDK 路径
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

# 下载固定提交的 libaom、libjpeg-turbo、dav1d 与 Meson
.\scripts\bootstrap-native.ps1
.\scripts\flutter-local.ps1 pub get
.\scripts\flutter-local.ps1 build apk --release --flavor libre --target lib/main_libre.dart --target-platform android-arm64
```

产物位于 `build/app/outputs/flutter-apk/app-libre-release.apk`。当前默认使用本机调试签名；自行发行请配置自己的安全签名流程，勿提交密钥。本项目尚未对全新构建环境做可复现构建验收。

Flutter 定向测试：

```powershell
.\scripts\flutter-local.ps1 test test/rhythm_library_test.dart
.\scripts\flutter-local.ps1 analyze lib/rhythm test/rhythm_library_test.dart
```

私人截图、人工标注、OCR 原始语料、设备日志、密钥、缓存和本地交接记录不随源码发布。涉及私人数据的实验回放需要自行准备数据；公开仓库仅提供合成文本回归样例。

## 致谢与来源

特别感谢 **Thibault Deckers（deckerst）及 Aves 全体贡献者**：

- 原项目：[Aves](https://github.com/deckerst/aves)
- 本项目沿用 Aves 的 Android/Flutter 工程、部分图片查看与手势组件，并参考相册界面、动画与交互实现。
- 保留原项目提交历史、版权声明与 BSD 3-Clause 许可证。仓库里仍有未使用的上游功能源码，并不表示本应用提供全部 Aves 功能。
- Aves 作者及贡献者与本衍生项目的实验功能、分类效果和数据管理风险无关。

同时感谢 Flutter、AndroidX、libaom、libjpeg-turbo、dav1d 及其他依赖项目。中英日识别使用 Google ML Kit 随包模型，相关组件适用各自的条款；本仓库公开不代表所有二进制依赖都采用 BSD 许可证。

## 许可证

项目沿用 **BSD 3-Clause License**，见 [LICENSE](LICENSE)。第三方组件保留各自许可证及专利声明；编解码许可证文本也包含在应用内的开源许可页中。
