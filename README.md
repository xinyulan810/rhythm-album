# 音游截图相册 · Rhythm Album

基于 **Aves** 改造的 Android 音游截图相册，使用 Flutter 与 Kotlin 开发。本项目是独立实验性衍生项目，不是 Aves 官方版本。

## 功能

- 自动收纳文件名含 `screenshot` 的截图，也支持手动导入。
- 按游戏分类，按相册内最新截图倒序排列；四宫格封面、实时筛选的相册命名下拉框。
- 中英日离线 OCR、文字搜索、AP（含 Pure Memory）与 FC 标签识别。
- 按游戏位置提取曲名，内置 Arcaea 曲库和别名用于纠错与搜索；支持自制曲及手动确认。
- 歌名识别区域按画面布局保存，可点选文字或拖动区域调整，不受相册改名影响；Arcaea 的 MAX RECALL 和 MAX COMBO 界面分别设置。
- 未确认曲名也可查找同曲或文字相似截图；全屏显示曲名，详情提供缩略图跳转。
- 搜索框一键清空，聚焦时显示最近 5 条搜索记录；支持中文拼音（含多音字）和日文假名罗马音搜索。
- 同曲截图优先同音游、再按日期倒序，支持长按多选移入回收站；相册和照片角落提示未识别曲名，可从多选操作栏选择歌名位置。
- 已导入图片按设备资源使用 1–4 路并行补识别；新图复用整图 OCR 结果提取曲名，显示处理进度。
- 全屏滑动浏览、缩放、下滑返回，多选、合并、收藏、分享、批量导出和回收站。
- 全分辨率高质量有损编码：速度优先使用 4:4:4 JPEG，体积优先使用实验性 AV1 方案。
- 可选“代管截图”：首次启动选择，设置中可关闭。可在必要文件检查后批量请求系统删除；另可授权媒体管理权限并明确开启前台自动清理。
- BanG Dream! 抽卡图使用独立内部类别，显示在同一个 BanG Dream! 相册。

## 当前状态与重要提醒

当前源码版本为 **0.4.0+21，实验性版本**。不要把本应用作为重要图片的唯一存储位置。

下载 ARM64 安装包：[GitHub Releases](https://github.com/xinyulan810/rhythm-album/releases/latest)。本次安装包使用本机调试密钥签名，更新安装需要与已安装版本的签名一致。

**压缩不是无损存档。代管删除原图后，清空应用数据或卸载会丢失尚未导出的图片。请先备份，再测试。**

当前已知限制：

1. 开启代管后，处理队列优先完成已导入图片的压缩和 OCR，以便逐步清理原图。已分类但未压缩的图片暂时也会显示在“其他”，这是已知显示逻辑问题，不代表全部识别失败。
2. 开启代管后可手动清理已处理的截图；Android 12+ 另可授权媒体管理权限并明确开启自动清理，应用在前台时处理。Android 11 或未授权时仍需系统确认。快速模式只检查已发布归档及必要文件是否存在，不重新哈希或解码，完整性风险高于此前版本。
3. 当前代管仅处理 MediaStore 中符合 screenshot 命名的图片。手动文档选择器来源、压缩无收益的原样收纳图片不参与清理。
4. 分类与成绩标签可能误判；开发集回放结果不能代表所有游戏和截图的识别率。部分新分类规则尚未完成发行包真机回归。
5. 主要在单台 ARM64 Android 设备上测试；不承诺跨设备稳定 60fps。系统删除/取消和删除后浏览、导出流程仍在实测中。
6. 曲名相似结果可能误匹配，不会自动合并歌曲或删除旧成绩。批量并行识别的实际提速及裁剪 OCR 准确率尚未完成真机验收。

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

Arcaea 曲库与别名来自 [Arcaea Toolbelt Data](https://github.com/DarrenDanielDay/arcaea-toolbelt-data) 的固定快照，遵循 MIT 许可证；来源提交记录在 `android/app/src/main/assets/rhythm-arcaea.json`，许可证随应用保留。

中文拼音读音来自 [python-pinyin](https://github.com/mozillazg/python-pinyin) 的固定快照（MIT），仅保留基本汉字区及多音字读音。日语只转换平假名和片假名，不包含日语汉字读音词典；两种读音搜索均离线运行。

## 许可证

项目沿用 **BSD 3-Clause License**，见 [LICENSE](LICENSE)。第三方组件保留各自许可证及专利声明；编解码许可证文本也包含在应用内的开源许可页中。
