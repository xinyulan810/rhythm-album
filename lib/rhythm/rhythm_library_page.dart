import 'dart:async';
import 'dart:io';
import 'dart:ui' show FrameTiming;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart' show listEquals;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_staggered_animations/flutter_staggered_animations.dart';
import 'package:aves/widgets/common/fx/transitions.dart';
import 'package:aves/widgets/common/basic/draggable_scrollbar/scrollbar.dart';
import 'package:aves/widgets/common/grid/draggable_thumb_label.dart';
import 'package:aves/widgets/common/identity/scroll_thumb.dart';

import 'rhythm_gallery_controller.dart';
import 'rhythm_photo_actions.dart';
import 'rhythm_viewer.dart';
import 'rhythm_name_dialog.dart';
import 'rhythm_native_licenses.dart';
import 'rhythm_motion.dart';
import 'rhythm_song.dart';
import 'rhythm_song_layout.dart';

class RhythmLibraryPage extends StatefulWidget {
  const new({super.key});
  @override
  State<RhythmLibraryPage> createState() => _RhythmLibraryPageState();
}

class _RhythmLibraryPageState extends State<RhythmLibraryPage> with WidgetsBindingObserver {
  final gallery = RhythmGalleryController();
  final search = TextEditingController();
  final searchFocus = FocusNode();
  List<String> searchHistory = [];
  bool historyEdited = false;
  final scroll = ScrollController();
  final albumScroll = ScrollController();
  final selected = <String>{};
  final selectedAlbums = <String>{};
  bool selecting = false;
  bool managedPromptShown = false;
  bool managedAutoRunning = false;
  Map? transfer;
  Map? managedProgress;
  Timer? poll;
  Timer? debounce;
  bool active = true;
  bool inViewer = false;
  String timingPhase = 'startup';
  double gridWidth = 0;
  int _groupVersion = -1, _rowVersion = -1, _rowColumns = 0;
  Map<String, List<int>> _groups = {};
  final _gridRows = <({String? date, List<int> photos, double offset, double extent})>[];
  final _photoOffsets = <int, double>{};
  double _rowWidth = 0;
  bool busy = false;
  bool showingAlbums = true;
  String collectionTitle = '全部截图';
  int frames = 0, slowFrames = 0;
  int over60Frames = 0;
  final _buildTimes = <double>[], _rasterTimes = <double>[];
  double maxBuildMs = 0, maxRasterMs = 0;

  @override
  void initState() {
    super.initState();
    searchFocus.addListener(_searchFocusChanged);
    unawaited(_loadSearchHistory());
    registerRhythmNativeLicenses();
    WidgetsBinding.instance.addObserver(this);
    RhythmGalleryController.channel.setMethodCallHandler((call) async {
      if (call.method == 'managedProgress') {
        if (mounted) setState(() => managedProgress = call.arguments as Map);
        return null;
      }
      if (call.method == 'transferProgress') {
        if (mounted) setState(() => transfer = call.arguments as Map);
        return null;
      }
      if (call.method != 'benchmarkDisplay') throw MissingPluginException();
      final args = call.arguments as Map;
      final result = <String, Object>{};
      List<int>? reference;
      for (final type in ['png', 'bmp']) {
        final watch = Stopwatch()..start();
        final bytes = await File(args[type] as String).readAsBytes();
        final codec = await ui.instantiateImageCodec(bytes);
        final frame = await codec.getNextFrame();
        result['${type}DecodeMs'] = watch.elapsedMilliseconds;
        result['${type}Width'] = frame.image.width;
        result['${type}Height'] = frame.image.height;
        final pixels = (await frame.image.toByteData(format: ui.ImageByteFormat.rawRgba))!.buffer.asUint8List();
        if (reference == null) {
          reference = pixels;
        } else {
          result['pixelMatch'] = listEquals(reference, pixels);
        }
        frame.image.dispose();
        codec.dispose();
      }
      return result;
    });
    WidgetsBinding.instance.addTimingsCallback(_timings);
    final startup = Stopwatch()..start();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(gallery.action('uiTiming', {'kind': 'home_first_frame', 'ms': startup.elapsedMilliseconds}).catchError((Object _) => null));
      gallery.framePhases.mark('albums_idle');
    });
    gallery.addListener(_changed);
    scroll.addListener(() {
      if (scroll.position.extentAfter < 800) unawaited(gallery.loadMore());
    });
    unawaited(gallery.refresh());
    poll = Timer.periodic(const Duration(seconds: 3), (_) {
      if (active && showingAlbums && !inViewer && !busy && !selecting && !gallery.loading) {
        unawaited(gallery.refresh());
      } else if (active && !inViewer && !gallery.loading) {
        unawaited(gallery.refreshSongProgress());
      }
    });
  }

  Future<void> _loadSearchHistory() async {
    try {
      final values = await gallery.action('searchHistory');
      if (mounted && !historyEdited && values is List) setState(() => searchHistory = values.cast<String>().take(5).toList());
    } catch (_) {}
  }

  void _searchFocusChanged() {
    if (!mounted) return;
    if (!searchFocus.hasFocus) _rememberSearch(search.text);
    setState(() {});
  }

  void _rememberSearch(String text) {
    final value = text.trim();
    if (value.isEmpty) return;
    historyEdited = true;
    setState(() => searchHistory = [value, ...searchHistory.where((s) => s.toLowerCase() != value.toLowerCase())].take(5).toList());
    unawaited(gallery.action('rememberSearch', {'query': value}).catchError((Object _) => null));
  }

  void _useSearch(String value) {
    debounce?.cancel();
    search.value = TextEditingValue(text: value, selection: TextSelection.collapsed(offset: value.length));
    if (value.isNotEmpty) {
      _rememberSearch(value);
      setState(() => showingAlbums = false);
    }
    _filter(() => gallery.search = value);
  }

  void _returnToAlbums() {
    debounce?.cancel();
    search.clear();
    searchFocus.unfocus();
    gallery.framePhases.mark('albums_idle');
    setState(() {
      showingAlbums = true;
      collectionTitle = '全部截图';
    });
    _filter(() {
      gallery.search = '';
      gallery.category = '';
      gallery.album = '';
      gallery.favorite = false;
      gallery.achievement = '';
      gallery.trash = false;
      gallery.songId = 0;
      gallery.relatedTo = '';
      gallery.excludeId = '';
    });
  }

  Future<void> _chooseSongLayout() async {
    if (selected.length != 1) return;
    final id = selected.single;
    await _run(() async {
      final data = await gallery.action('songInfo', {'id': id});
      if (data is! Map || !mounted) return null;
      final row = Map<String,dynamic>.from(data);
      final path = await gallery.path(row);
      if (!mounted || path == null) return null;
      await Navigator.push<Map<String,dynamic>>(context, MaterialPageRoute(
        builder: (_) => RhythmSongLayoutPage(row: row,imagePath: path)));
      return null;
    });
  }

  void _changed() {
    if (!mounted) return;
    if (inViewer) return;
    if (!managedAutoRunning && active && !busy && !selecting &&
        gallery.stats['managedScreenshots'] == true &&
        gallery.stats['managedAutomatic'] == true &&
        gallery.stats['managedPermission'] == true &&
        (gallery.stats['managedPending'] as num? ?? 0) > 0) {
      managedAutoRunning = true;
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (mounted && active && !busy && !inViewer && !selecting) await _cleanManaged(automatic: true);
        managedAutoRunning = false;
      });
    }
    if (!managedPromptShown && gallery.stats['managedChoiceMade'] == false) {
      managedPromptShown = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) unawaited(_chooseManaged(first: true));
      });
    }
    setState(() {});
    // Load metadata only, not full images, so the thumb represents the whole
    // collection. Bounded requests retain normal first-screen responsiveness.
    if (!showingAlbums && !gallery.loading && gallery.more && gallery.error == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && !showingAlbums) unawaited(gallery.loadMore(limit: 240));
      });
    }
    // Album/grid views only need thumbnails. Full-size prefetch starts in the viewer.
  }

  void _timings(List<FrameTiming> timings) {
    final hz = WidgetsBinding.instance.platformDispatcher.views.first.display.refreshRate;
    final budget = 1000 / (hz > 0 ? hz : 60);
    for (final timing in timings) {
      final phase = gallery.framePhases.at(timing.timestampInMicroseconds(ui.FramePhase.buildStart));
      if (phase != timingPhase) {
        _flushTimings();
        timingPhase = phase;
      }
      final build = timing.buildDuration.inMicroseconds / 1000;
      final raster = timing.rasterDuration.inMicroseconds / 1000;
      frames++;
      if (build > budget || raster > budget) slowFrames++;
      if (build > 1000 / 60 || raster > 1000 / 60) over60Frames++;
      _buildTimes.add(build);
      _rasterTimes.add(raster);
      if (build > maxBuildMs) maxBuildMs = build;
      if (raster > maxRasterMs) maxRasterMs = raster;
    }
    if (frames >= 120) _flushTimings();
  }

  void _flushTimings() {
    if (frames == 0) return;
    _buildTimes.sort();
    _rasterTimes.sort();
    unawaited(
      gallery
          .action('uiTiming', {
            'kind': timingPhase,
            'frames': frames,
            'slowFrames': slowFrames,
            'over60Frames': over60Frames,
            'p95BuildMs': _buildTimes[(_buildTimes.length * .95).floor().clamp(0, _buildTimes.length - 1)],
            'p95RasterMs': _rasterTimes[(_rasterTimes.length * .95).floor().clamp(0, _rasterTimes.length - 1)],
            'maxBuildMs': maxBuildMs,
            'maxRasterMs': maxRasterMs,
          })
          .catchError((Object _) => null),
    );
    frames = 0;
    slowFrames = 0;
    over60Frames = 0;
    _buildTimes.clear();
    _rasterTimes.clear();
    maxBuildMs = 0;
    maxRasterMs = 0;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    active = state == AppLifecycleState.resumed;
    if (active) {
      unawaited(gallery.action('scan').catchError((Object _) => null));
      if (!inViewer) unawaited(gallery.refresh());
    }
  }

  @override
  void didHaveMemoryPressure() => gallery.clearPrefetch();
  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    RhythmGalleryController.channel.setMethodCallHandler(null);
    WidgetsBinding.instance.removeTimingsCallback(_timings);
    _flushTimings();
    poll?.cancel();
    debounce?.cancel();
    gallery.removeListener(_changed);
    gallery.dispose();
    search.dispose();
    searchFocus.removeListener(_searchFocusChanged);
    searchFocus.dispose();
    scroll.dispose();
    albumScroll.dispose();
    super.dispose();
  }

  void _toast(String message) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _run(Future<Object?> Function() work, {String? success}) async {
    if (busy) return;
    setState(() => busy = true);
    try {
      final result = await work();
      if (result is Map && result.containsKey('completed')) {
        _toast('${result['cancelled'] == true ? '已取消 · ' : ''}完成 ${result['completed']} / ${result['total']} 张，失败 ${result['failed']} 张');
      } else if (success != null) {
        _toast(success);
      }
      if (mounted) await gallery.refresh();
    } catch (e) {
      _toast(RhythmGalleryController.message(e));
    } finally {
      _flushTimings();
      if (mounted) {
        setState(() {
          busy = false;
          transfer = null;
          managedProgress = null;
        });
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _changed();
        });
      }
    }
  }

  Future<void> _enableAuto(bool enabled) => _run(() async {
    await gallery.action('enableAuto', {'enabled': enabled});
    if (enabled) {
      await gallery.action('notifications');
      await gallery.action('scan');
    }
    return null;
  });
  void _filter(VoidCallback change) {
    change();
    selected.clear();
    selecting = false;
    if (scroll.hasClients) scroll.jumpTo(0);
    unawaited(gallery.refresh());
  }

  Future<void> _open(int index) async {
    if (inViewer || busy) return;
    searchFocus.unfocus();
    gallery.openWatch = Stopwatch()..start();
    gallery.openedId = gallery.items[index]['id'] as String;
    final dataBefore = gallery.dataVersion;
    gallery.viewerChanged = false;
    inViewer = true;
    gallery.framePhases.mark('viewer_open');
    try {
      await gallery.action('viewerStart');
      if (!mounted) return;
      final recognized = await gallery.action('songInfo', {'id': gallery.items[index]['id']});
      if (!mounted) return;
      if (recognized is Map) gallery.items[index].addAll(Map<String, dynamic>.from(recognized));
      // Fetch and decode the selected image in parallel with the Hero flight,
      // using the same provider/cache key as the viewer. Do not warm neighbours
      // until the transition finishes.
      unawaited(
        gallery
            .path(gallery.items[index])
            .then((path) async {
              if (mounted && inViewer && path != null) {
                await precacheImage(rhythmDisplayImage(context, gallery.items[index], path), context, onError: (_, _) {});
              }
            })
            .catchError((Object _) {}),
      );
      final route = PageRouteBuilder<void>(
        opaque: false,
        transitionDuration: MediaQuery.disableAnimationsOf(context) ? Duration.zero : const Duration(milliseconds: 200),
        reverseTransitionDuration: MediaQuery.disableAnimationsOf(context) ? Duration.zero : const Duration(milliseconds: 200),
        pageBuilder: (_, _, _) => RhythmViewer(gallery: gallery, initialIndex: index, onPhotoChanged: _trackPhoto),
        // No full-screen opacity layer; the shared image animates in the Hero.
        transitionsBuilder: (_, _, _, child) => child,
      );
      await Navigator.push<void>(context, route);
      await route.completed;
    } catch (e) {
      _toast(RhythmGalleryController.message(e));
    } finally {
      await gallery.action('viewEnd').catchError((Object _) => null);
      inViewer = false;
      gallery.framePhases.mark('gallery_idle');
      // Route.popped fires at the beginning of the reverse transition. Never
      // rebuild the gallery or resume OCR until the visual transition is over.
      if (mounted && gallery.viewerChanged) {
        await gallery.refresh();
      } else if (mounted && dataBefore != gallery.dataVersion) {
        _changed();
      }
    }
  }

  Map<String, List<int>> get photoGroups {
    if (_groupVersion == gallery.dataVersion) return _groups;
    final result = <String, List<int>>{};
    for (var i = 0; i < gallery.items.length; i++) {
      result.putIfAbsent(rhythmDate(gallery.items[i]), () => []).add(i);
    }
    _groupVersion = gallery.dataVersion;
    return _groups = result;
  }

  void _layoutRows() {
    if (_rowVersion == gallery.dataVersion && _rowWidth == gridWidth) return;
    _rowVersion = gallery.dataVersion;
    _rowWidth = gridWidth;
    _gridRows.clear();
    _photoOffsets.clear();
    final width = (gridWidth - 6).clamp(1.0, double.infinity);
    _rowColumns = (width / 153).ceil().clamp(1, 100);
    final side = (width - (_rowColumns - 1) * 3) / _rowColumns;
    var offset = 0.0;
    for (final group in photoGroups.entries) {
      _gridRows.add((date: group.key, photos: const [], offset: offset, extent: 44));
      offset += 44;
      for (var start = 0; start < group.value.length; start += _rowColumns) {
        final photos = group.value.sublist(start, (start + _rowColumns).clamp(0, group.value.length));
        final extent = side + (start + _rowColumns < group.value.length ? 3 : 0);
        _gridRows.add((date: null, photos: photos, offset: offset, extent: extent));
        for (final index in photos) {
          _photoOffsets[index] = offset;
        }
        offset += extent;
      }
    }
  }

  ({double offset, int index}) _gridPosition({int? target, double? atOffset}) {
    _layoutRows();
    if (target != null) return (offset: _photoOffsets[target] ?? 0, index: target);
    if (_gridRows.isEmpty) return (offset: 0, index: 0);
    var low = 0, high = _gridRows.length - 1;
    while (low < high) {
      final mid = (low + high) ~/ 2;
      final row = _gridRows[mid];
      if (row.offset + row.extent <= (atOffset ?? 0)) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    if (_gridRows[low].photos.isEmpty && low + 1 < _gridRows.length) low++;
    final row = _gridRows[low];
    return (offset: row.offset, index: row.photos.isEmpty ? 0 : row.photos.first);
  }

  void _trackPhoto(int index) {
    if (!scroll.hasClients || index >= gallery.items.length) return;
    final offset = _gridPosition(target: index).offset;
    final viewport = scroll.position.viewportDimension;
    if (offset < scroll.offset || offset + 150 > scroll.offset + viewport) {
      scroll.jumpTo((offset - viewport / 3).clamp(0, scroll.position.maxScrollExtent));
    }
  }

  Future<void> _batch(String operation) async {
    final ids = selected.toList();
    if (ids.isEmpty) return;
    Object? value;
    if (operation == 'category' || operation == 'album') {
      final name = await _categoryName(
        operation == 'album' ? '移入相册' : '修正音游分类',
        '',
        correction: true,
        helperText: '选择已有相册或输入新名称，所选图片将归入首页对应相册。\n选择“其他”移出音游相册；留空创建未命名相册。',
      );
      if (name == null) return;
      await _run(() async {
        await gallery.action('correctCategory', {'ids': ids, 'name': name, 'other': name == '其他'});
        selected.clear();
        return null;
      });
      return;
    }
    if (operation == 'achievement') {
      value = await showDialog<String>(
        context: context,
        builder: (context) => SimpleDialog(
          title: const Text('成绩标签'),
          children: [
            for (final entry in [('AP', 'AP · All Perfect / Pure Memory'), ('FC', 'FC · Full Combo'), ('', '无成绩标签'), ('auto', '恢复自动识别')]) SimpleDialogOption(onPressed: () => Navigator.pop(context, entry.$1), child: Text(entry.$2)),
          ],
        ),
      );
      if (value == null) return;
    }
    if ((operation == 'trash' || operation == 'purge') && mounted) {
      final accepted = await confirmRhythm(context, operation == 'trash' ? '移入回收站？' : '永久删除所选照片？', operation == 'trash' ? '将本相册中的 ${ids.length} 张照片移入回收站，可恢复。' : '删除 ${ids.length} 张本地图片，不可撤销。已代管且未导出的图片将丢失；其他图片仍依赖的参考数据会保留。');
      if (!accepted) return;
    }
    await _run(() async {
      final result = ['share', 'export', 'exportDirectory'].contains(operation)
          ? await gallery.action(operation, {'ids': ids})
          : await gallery.action('batch', {'ids': ids, 'operation': operation, 'value': operation == 'favorite' ? true : value});
      selected.clear();
      return result;
    }, success: operation == 'share' ? null : '操作完成');
  }

  Future<void> _chooseManaged({bool first = false}) async {
    final enabled = await showDialog<bool>(
      context: context,
      barrierDismissible: !first,
      builder: (context) => PopScope(
        canPop: !first,
        child: AlertDialog(
          title: const Text('是否代管截图？'),
          content: const SingleChildScrollView(child: Text(
            '关闭：保留系统原图，只在应用内收纳副本。\n\n'
            '开启：图片压缩与文字识别完成后，可批量清理系统原图。可在设置中另行开启自动清理并授予媒体管理权限。\n\n'
            '压缩为高质量有损编码，不保留原文件的全部信息。删除后，图片由本应用保存；清空应用数据或卸载前必须先导出，否则会丢失图片。\n\n'
            '可随时在设置中关闭；关闭不会恢复已删除的原图。')),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('关闭，保留原图')),
            if (gallery.stats['managedSupported'] == true)
              FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('开启代管')),
          ],
        ),
      ),
    );
    if (enabled != null && mounted) await _run(() => gallery.action('managedMode', {'enabled': enabled}));
  }

  Future<void> _cleanManaged({bool automatic = false}) => _run(() async {
    setState(() => managedProgress = {'phase': '准备清理', 'completed': 0, 'total': 0, 'failed': 0});
    final result = await gallery.action('deleteManagedSources') as Map?;
    if (mounted && !automatic) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(
      result?['cancelled'] == true ? '已取消，原图保留' : '已清理 ${result?['removed'] ?? 0} 张原图；其余保留，可在设置查看待处理数量',
      )));
    }
    return null;
  });

  Future<void> _settings() async {
    final stats = gallery.stats;
    final budget = stats['budget'] as Map? ?? {};
    final action = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                title: const Text('代管截图'),
                subtitle: Text('待清理 ${stats['managedPending'] ?? 0} 张 · 验证失败 ${stats['managedErrors'] ?? 0} 张\n清空应用数据或卸载前，请先导出代管图片'),
                trailing: Switch(value: stats['managedScreenshots'] == true,
                  onChanged: (_) => Navigator.pop(context, 'managedMode')),
              ),
              if (stats['managedScreenshots'] == true)
                ListTile(title: const Text('清理已完成截图的系统原图'), subtitle: const Text('当前批次最多 1000 张；不会删除未完成的图片'),
                  onTap: () => Navigator.pop(context, 'cleanManaged')),
              if (stats['managedScreenshots'] == true)
                ListTile(
                  title: const Text('自动清理系统原图'),
                  subtitle: Text(stats['managedPermission'] == true
                      ? '应用在前台时自动清理已完成的截图；清空数据或卸载前请先导出'
                      : '需 Android 12+ 媒体管理权限；允许后再次开启'),
                  trailing: Switch(value: stats['managedAutomatic'] == true && stats['managedPermission'] == true,
                    onChanged: (_) => Navigator.pop(context, 'managedAutomatic')),
                ),
              if ((stats['managedErrors'] as num? ?? 0) > 0)
                ListTile(title: const Text('重试原图验证失败项'), onTap: () => Navigator.pop(context, 'retryManaged')),
              ListTile(
                title: const Text('自动收纳 screenshot 图片'),
                subtitle: Text(
                  '图片访问：${switch (stats['mediaAccess']) {
                    'full' => '全部照片',
                    'partial' => '仅所选照片',
                    _ => '尚未授权',
                  }}',
                ),
                trailing: Switch(value: stats['autoImport'] == true, onChanged: (_) => Navigator.pop(context, 'auto')),
              ),
              ListTile(leading: const Icon(Icons.photo_library_outlined), title: const Text('照片访问权限'), onTap: () => Navigator.pop(context, 'permission')),
              ListTile(leading: const Icon(Icons.sync), title: const Text('立即扫描新截图'), onTap: () => Navigator.pop(context, 'scan')),
              ListTile(leading: const Icon(Icons.notifications_outlined), title: const Text('后台通知设置'), onTap: () => Navigator.pop(context, 'notificationSettings')),
              ListTile(leading: Icon(stats['paused'] == true ? Icons.play_arrow : Icons.pause), title: Text(stats['paused'] == true ? '继续后台处理' : '暂停后台处理'), onTap: () => Navigator.pop(context, stats['paused'] == true ? 'resume' : 'pause')),
              ListTile(
                leading: const Icon(Icons.speed),
                title: const Text('自动性能调度'),
                subtitle: Text(
                  '${stats['encodingMode'] == 'compact' ? 'AV1 编码 ${budget['threads'] ?? 1} / ${budget['threadCeiling'] ?? 1} 线程' : '快速编码 · 单张任务'} · '
                  '图像缓存 ${budget['cacheMb'] ?? 48} MiB · 邻近预读 ${budget['prefetch'] ?? 1} 张\n'
                  '根据内存、温度、省电模式及近期耗时调整',
                ),
              ),
              ListTile(
                leading: const Icon(Icons.compress),
                title: Text(stats['encodingMode'] == 'compact' ? '压缩模式：体积优先' : '压缩模式：速度优先'),
                subtitle: const Text('速度优先使用高质量 4:4:4 JPEG；体积优先使用 AV1。仅影响新处理的图片。'),
                onTap: () => Navigator.pop(context, 'encodingMode'),
              ),
              ListTile(leading: const Icon(Icons.bug_report_outlined), title: const Text('复制性能日志'), onTap: () => Navigator.pop(context, 'logs')),
              ListTile(
                leading: const Icon(Icons.text_snippet_outlined),
                title: const Text('中英日离线文字识别'),
                subtitle: Text('待识别 ${stats['ocrPending'] ?? 0} 张 · 失败 ${stats['ocrErrors'] ?? 0} 张\n支持文字搜索、AP / FC；失败项可点此重试'),
                onTap: () => Navigator.pop(context, 'retryOcr'),
              ),
              if (stats['canUndoCategory'] == true) ListTile(leading: const Icon(Icons.undo), title: const Text('撤销上次相册命名 / 合并'), onTap: () => Navigator.pop(context, 'undoCategory')),
              ListTile(
                leading: const Icon(Icons.music_note_outlined),
                title: const Text('曲名识别 · 试验版'),
                subtitle: Text('待识别 ${stats['songPending'] ?? 0} 张 · 失败 ${stats['songErrors'] ?? 0} 张\n点此重跑自动识别；人工确认的曲名保留'),
                onTap: () => Navigator.pop(context, 'songRetry'),
              ),
              ListTile(leading: const Icon(Icons.info_outline), title: const Text('关于音游相册 · 0.4.0'), subtitle: const Text('代管截图删除原图须系统确认。回收站不自动清空；卸载前请导出。'), onTap: () => Navigator.pop(context, 'about')),
              SwitchListTile(
                title: const Text('显示未识别曲名提示'),
                subtitle: const Text('在相册和照片上显示提示角标'),
                value: stats['showSongMissing'] != false,
                onChanged: (_) => Navigator.pop(context, 'showSongMissing'),
              ),
            ],
          ),
        ),
      ),
    );
    if (!mounted || action == null) return;
    switch (action) {
      case 'showSongMissing':
        await _run(() => gallery.action('showSongMissing', {'enabled': stats['showSongMissing'] == false}));
      case 'managedMode':
        if (stats['managedScreenshots'] == true) {
          await _run(() => gallery.action('managedMode', {'enabled': false}));
        } else {
          await _chooseManaged();
        }
      case 'cleanManaged':
        await _cleanManaged();
      case 'managedAutomatic':
        if (stats['managedAutomatic'] == true && stats['managedPermission'] == true) {
          await _run(() => gallery.action('managedAutomatic', {'enabled': false}));
        } else if (stats['managedPermission'] != true) {
          final accepted = await confirmRhythm(context, '允许自动清理原图？',
              '授权并开启后，应用在前台会自动删除已处理截图的系统原图。压缩有损，清空数据或卸载前必须先导出。系统授权后请再次开启此开关。');
          if (accepted) await _run(() => gallery.action('managedPermissionSettings'));
        } else {
          final accepted = await confirmRhythm(context, '开启自动清理原图？',
              '已处理截图的系统原图会自动删除。压缩有损，清空数据或卸载前必须先导出。');
          if (accepted) await _run(() => gallery.action('managedAutomatic', {'enabled': true}));
        }
      case 'auto':
        await _enableAuto(stats['autoImport'] != true);
      case 'permission':
        await _run(() => gallery.action('appSettings'));
      case 'encodingMode':
        final mode = await showDialog<String>(
          context: context,
          builder: (context) => SimpleDialog(
            title: const Text('压缩模式'),
            children: [
              SimpleDialogOption(onPressed: () => Navigator.pop(context, 'fast'), child: const Text('速度优先 · 4:4:4 JPEG')),
              SimpleDialogOption(onPressed: () => Navigator.pop(context, 'compact'), child: const Text('体积优先 · AV1')),
            ],
          ),
        );
        if (mode != null) await _run(() => gallery.action('encodingMode', {'mode': mode}));
      case 'logs':
        await _run(() async {
          final log = await gallery.action('diagnostics');
          await Clipboard.setData(ClipboardData(text: log as String? ?? '暂无日志'));
          return null;
        }, success: '性能日志已复制');
      case 'about':
        showLicensePage(context: context, applicationName: '音游相册', applicationVersion: '0.4.0');
      default:
        await _run(() => gallery.action(action));
    }
  }

  @override
  Widget build(BuildContext context) {
    final albums = (gallery.stats['albums'] as List? ?? []).cast<String>();
    return PopScope(
      canPop: !selecting && !gallery.trash && showingAlbums,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) {
          if (selecting) {
            _exitSelection();
          } else if (gallery.trash) {
            _filter(() => gallery.trash = false);
          } else {
            _returnToAlbums();
          }
        }
      },
      child: Scaffold(
        appBar: AppBar(
          leading: selecting || gallery.trash || !showingAlbums
              ? IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () {
                    if (selecting) {
                      _exitSelection();
                    } else if (gallery.trash) {
                      _filter(() => gallery.trash = false);
                    } else {
                      _returnToAlbums();
                    }
                  },
                )
              : null,
          title: AnimatedSwitcher(
            duration: MediaQuery.disableAnimationsOf(context) ? Duration.zero : const Duration(milliseconds: 180),
            child: Text(
              selecting
                  ? '已选 ${showingAlbums ? selectedAlbums.length : selected.length}${showingAlbums ? ' 个相册' : ' 张'}'
                  : gallery.trash
                  ? '回收站'
                  : showingAlbums
                  ? '音游相册'
                  : collectionTitle,
              key: ValueKey((selecting, showingAlbums, gallery.trash, collectionTitle)),
            ),
          ),
          actions: [
            if (selecting) ...[
              IconButton(tooltip: '全选', onPressed: busy ? null : _selectAll, icon: const Icon(Icons.select_all)),
              IconButton(
                tooltip: '取消选择',
                onPressed: busy
                    ? null
                    : () => setState(() {
                        selected.clear();
                        selectedAlbums.clear();
                      }),
                icon: const Icon(Icons.deselect),
              ),
            ],
            if (!selecting) ...[
              IconButton(tooltip: '多选', onPressed: () => setState(() => selecting = true), icon: const Icon(Icons.checklist)),
              IconButton(tooltip: '设置', onPressed: _settings, icon: const Icon(Icons.tune)),
            ],
          ],
        ),
        floatingActionButton: !selecting && !gallery.trash
            ? FloatingActionButton(
                tooltip: '手动导入',
                onPressed: busy
                    ? null
                    : () => _run(() async {
                        await gallery.action('notifications');
                        return gallery.action('pick', {'game': '', 'eligible': false});
                      }),
                child: const Icon(Icons.add_photo_alternate_outlined),
              )
            : null,
        bottomNavigationBar: AnimatedSwitcher(
          duration: MediaQuery.disableAnimationsOf(context) ? Duration.zero : const Duration(milliseconds: 180),
          transitionBuilder: AvesTransitions.formTransitionBuilder,
          layoutBuilder: (current, previous) => Column(mainAxisSize: MainAxisSize.min, children: [...previous, ?current]),
          child: !selecting
              ? const SizedBox(key: ValueKey('no-selection'))
              : showingAlbums
              ? SafeArea(
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: [
                        for (final item in [
                          ('合并到', Icons.merge, 'merge'),
                          ('分享', Icons.share_outlined, 'share'),
                          ('导出到文件夹', Icons.drive_file_move_outline, 'exportDirectory'),
                          ('收藏', Icons.favorite_border, 'favorite'),
                          ('移入回收站', Icons.delete_outline, 'trash'),
                        ])
                          TextButton.icon(onPressed: busy || selectedAlbums.isEmpty ? null : () => _albumAction(item.$3), icon: Icon(item.$2), label: Text(item.$1)),
                      ],
                    ),
                  ),
                )
              : SafeArea(
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: [
                        if (gallery.trash) ...[
                          _batchButton('恢复', Icons.restore, 'restore'),
                          _batchButton('永久删除', Icons.delete_forever, 'purge'),
                        ] else ...[
                          TextButton.icon(
                            onPressed: busy || selected.length != 1 ? null : _chooseSongLayout,
                            icon: const Icon(Icons.document_scanner_outlined), label: const Text('选择歌名位置')),
                          _batchButton('收藏', Icons.favorite_border, 'favorite'),
                          _batchButton('相册', Icons.folder_outlined, 'album'),
                          _batchButton('分类', Icons.category_outlined, 'category'),
                          _batchButton('成绩标签', Icons.label_outline, 'achievement'),
                          _batchButton('分享', Icons.share_outlined, 'share'),
                          _batchButton('保存', Icons.save_alt, 'export'),
                          _batchButton('文件夹', Icons.drive_file_move_outline, 'exportDirectory'),
                          _batchButton('删除', Icons.delete_outline, 'trash'),
                        ],
                      ],
                    ),
                  ),
                ),
        ),
        body: Column(
          children: [
            SizedBox(height: 2, child: busy || gallery.loading ? const LinearProgressIndicator(minHeight: 2) : null),
            if (!selecting && gallery.stats['managedScreenshots'] == true &&
                (gallery.stats['managedPending'] as num? ?? 0) > 0)
              ListTile(dense: true, title: Text('${gallery.stats['managedPending']} 张截图可清理系统原图'),
                trailing: TextButton(onPressed: busy ? null : _cleanManaged, child: const Text('清理原图'))),
            if (managedProgress != null && busy)
              ListTile(
                dense: true,
                title: Text('${managedProgress!['phase']} ${managedProgress!['completed']} / ${managedProgress!['total']} · 失败 ${managedProgress!['failed']}'),
                subtitle: (managedProgress!['total'] as num? ?? 0) > 0
                    ? LinearProgressIndicator(value: ((managedProgress!['completed'] as num? ?? 0) /
                        (managedProgress!['total'] as num)).clamp(0.0, 1.0))
                    : null,
                trailing: managedProgress!['phase'] == '准备清理'
                    ? TextButton(onPressed: () => gallery.action('cancelManaged'), child: const Text('取消'))
                    : null,
              ),
            if (transfer != null)
              ListTile(
                dense: true,
                title: Text('正在处理 ${transfer!['completed']} / ${transfer!['total']} 张 · 失败 ${transfer!['failed']}'),
                trailing: TextButton(onPressed: () => gallery.action('cancelTransfer'), child: const Text('取消')),
              ),
            RhythmSongProgress(progress: Map<String, dynamic>.from(gallery.stats['songProgress'] as Map? ?? {})),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
              child: TextField(
                controller: search,
                focusNode: searchFocus,
                textInputAction: TextInputAction.search,
                decoration: InputDecoration(
                  hintText: '搜索图片文字、曲名、游戏、AP / FC',
                  prefixIcon: const Icon(Icons.search),
                  suffixIcon: search.text.isEmpty ? null : IconButton(
                    tooltip: '清空搜索', icon: const Icon(Icons.close),
                    onPressed: () {
                      _useSearch('');
                      searchFocus.requestFocus();
                    }),
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(24)),
                  isDense: true,
                ),
                onChanged: (value) {
                  debounce?.cancel();
                  setState(() { if (value.isNotEmpty) showingAlbums = false; });
                  debounce = Timer(const Duration(milliseconds: 300), () => _filter(() => gallery.search = value));
                },
                onSubmitted: _useSearch,
              ),
            ),
            if (searchFocus.hasFocus && searchHistory.isNotEmpty)
              SizedBox(height: 42, child: ListView.separated(
                key: const Key('rhythm-search-history'),
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                itemCount: searchHistory.length,
                separatorBuilder: (_, _) => const SizedBox(width: 8),
                itemBuilder: (_, index) => InputChip(
                  label: ConstrainedBox(constraints: const BoxConstraints(maxWidth: 180),
                    child: Text(searchHistory[index], maxLines: 1, overflow: TextOverflow.ellipsis)),
                  onPressed: () => _useSearch(searchHistory[index]),
                ),
              )),
            if (!showingAlbums)
              SizedBox(
                height: 48,
                child: ListView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  children: [
                    _chip(
                      '全部',
                      !gallery.favorite && gallery.album.isEmpty && !gallery.trash && gallery.achievement.isEmpty,
                      () => _filter(() {
                        gallery.favorite = false;
                        gallery.album = '';
                        gallery.trash = false;
                        gallery.category = '';
                        gallery.achievement = '';
                      }),
                    ),
                    _chip(
                      '收藏',
                      gallery.favorite,
                      () => _filter(() {
                        gallery.favorite = !gallery.favorite;
                        gallery.trash = false;
                      }),
                    ),
                    for (final tag in ['AP', 'FC']) _chip(tag, gallery.achievement == tag, () => _filter(() => gallery.achievement = gallery.achievement == tag ? '' : tag)),
                    for (final album in albums)
                      _chip(
                        album,
                        gallery.album == album,
                        () => _filter(() {
                          gallery.album = gallery.album == album ? '' : album;
                          gallery.trash = false;
                        }),
                      ),
                    _chip(
                      '回收站',
                      gallery.trash,
                      () => _filter(() {
                        gallery.trash = !gallery.trash;
                        gallery.favorite = false;
                        gallery.album = '';
                      }),
                    ),
                  ],
                ),
              ),
            if (gallery.stats['autoImport'] != true && !gallery.trash)
              ListTile(
                dense: true,
                leading: const Icon(Icons.auto_awesome_outlined),
                title: const Text('自动收纳截图'),
                subtitle: const Text('授权后自动收纳名称含 screenshot 的图片'),
                trailing: TextButton(onPressed: busy ? null : () => _enableAuto(true), child: const Text('开启')),
              ),
            if (gallery.stats['autoImport'] == true && gallery.stats['mediaAccess'] != 'full')
              ListTile(
                dense: true,
                title: Text(gallery.stats['mediaAccess'] == 'partial' ? '仅能自动收纳已授权的照片' : '自动收纳需要照片访问权限'),
                trailing: TextButton(onPressed: () => _enableAuto(true), child: const Text('授权')),
              ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  '${gallery.total} 张 · 待处理 ${gallery.stats['pending'] ?? 0}'
                  '${(gallery.stats['classifying'] as num? ?? 0) > 0 ? ' · 待分类 ${gallery.stats['classifying']}' : ''}'
                  '${(gallery.stats['ocrPending'] as num? ?? 0) > 0 ? ' · 待识别文字 ${gallery.stats['ocrPending']}' : ''}'
                  '${gallery.stats['paused'] == true ? ' · 已暂停' : ''}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            ),
            if (gallery.error != null)
              ListTile(
                title: Text(gallery.error!),
                trailing: TextButton(onPressed: gallery.refresh, child: const Text('重试')),
              ),
            for (final key in ['workerError', 'scanError'])
              if ((gallery.stats[key] as String? ?? '').isNotEmpty) Padding(padding: const EdgeInsets.all(8), child: Text(gallery.stats[key] as String)),
            Expanded(
              child: RhythmSurfaceTransition(
                identity: showingAlbums ? 'albums' : 'collection',
                child: showingAlbums
                    ? _albumHome()
                    : RefreshIndicator(
                        onRefresh: () async {
                          await gallery.action('scan');
                          await gallery.refresh();
                        },
                        child: LayoutBuilder(
                          builder: (context, constraints) {
                            gridWidth = constraints.maxWidth;
                            _layoutRows();
                            return DraggableScrollbar(
                              scrollThumbSize: AvesScrollThumb.thumbSize,
                              scrollThumbBuilder: AvesScrollThumb.builder(context: context, height: AvesScrollThumb.thumbHeight),
                              controller: scroll,
                              padding: const EdgeInsets.only(bottom: 84),
                              labelTextBuilder: (offset) => Material(
                                elevation: 4,
                                borderRadius: const BorderRadius.all(Radius.circular(16)),
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                                  child: Text(
                                    gallery.items.isEmpty ? '' : '${rhythmDate(gallery.items[_gridPosition(atOffset: offset).index])}\n${_gridPosition(atOffset: offset).index + 1} / ${gallery.total}${gallery.more ? ' · 正在加载索引' : ''}',
                                    style: const TextStyle(fontSize: 12),
                                  ),
                                ),
                              ),
                              crumbTextBuilder: (label) => DraggableCrumbLabel(label: label),
                              child: CustomScrollView(
                                controller: scroll,
                                physics: const AlwaysScrollableScrollPhysics(),
                                slivers: [
                                  if (gallery.items.isEmpty && !gallery.loading)
                                    SliverFillRemaining(
                                      hasScrollBody: false,
                                      child: Center(child: Text(gallery.trash ? '回收站是空的' : '还没有截图\n开启自动收纳，或点击 + 手动导入', textAlign: TextAlign.center)),
                                    ),
                                  SliverVariedExtentList.builder(
                                    itemCount: _gridRows.length,
                                    itemExtentBuilder: (i, _) => i < _gridRows.length ? _gridRows[i].extent : null,
                                    itemBuilder: (context, i) {
                                      final row = _gridRows[i];
                                      if (row.date != null) {
                                        return Padding(
                                          padding: const EdgeInsets.symmetric(horizontal: 16),
                                          child: Align(
                                            alignment: Alignment.centerLeft,
                                            child: Text(row.date!, style: Theme.of(context).textTheme.titleSmall),
                                          ),
                                        );
                                      }
                                      final side = ((gridWidth - 6) - (_rowColumns - 1) * 3) / _rowColumns;
                                      return Padding(
                                        padding: EdgeInsets.fromLTRB(3, 0, 3, row.extent - side),
                                        child: Row(
                                          children: [
                                            for (var c = 0; c < _rowColumns; c++) ...[
                                              if (c > 0) const SizedBox(width: 3),
                                              Expanded(child: c < row.photos.length ? RepaintBoundary(child: _tile(row.photos[c])) : const SizedBox()),
                                            ],
                                          ],
                                        ),
                                      );
                                    },
                                  ),
                                  if (gallery.more && gallery.items.isNotEmpty)
                                    SliverToBoxAdapter(
                                      child: TextButton(onPressed: gallery.loading ? null : gallery.loadMore, child: const Text('加载更多')),
                                    ),
                                  const SliverToBoxAdapter(child: SizedBox(height: 90)),
                                ],
                              ),
                            );
                          },
                        ),
                      ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<String?> _categoryName(String title, String initial, {bool correction = false, String? helperText}) async {
    final options = await gallery.action('categorySuggestions') as List?;
    if (!mounted) return null;
    return showDialog<String>(
      context: context,
      builder: (_) => RhythmNameDialog(
        title: title,
        initial: initial,
        helperText: helperText,
        options: [
          {'code': 'other', 'name': '其他', 'count': gallery.stats['otherCount'] ?? 0},
          ...(options ?? gallery.stats['categories'] as List? ?? []).cast<Map>().where((e) => e['name'] != '其他'),
        ],
        correction: correction,
      ),
    );
  }

  void _exitSelection() => setState(() {
    selecting = false;
    selected.clear();
    selectedAlbums.clear();
  });

  Future<void> _selectAll() async {
    if (showingAlbums) {
      setState(() => selectedAlbums.addAll(['', 'other', ...(gallery.stats['categories'] as List? ?? []).cast<Map>().map((e) => e['code'] as String)]));
    } else {
      await _run(() async {
        final ids = await gallery.action('selectionIds', gallery.filter) as List;
        selected.addAll(ids.cast<String>());
        return null;
      });
    }
  }

  Future<void> _renameAlbum(Map entry) async {
    final value = await _categoryName('相册命名', entry['name'] as String);
    if (value == '其他' && mounted && !await confirmRhythm(context, '移入其他？', '此相册的照片将归入“其他”，可在设置中撤销。')) return;
    if (value != null) await _run(() => gallery.action('renameCategory', {'code': entry['code'], 'name': value}));
  }

  Future<void> _albumMenu(Map entry) async {
    final code = entry['code'] as String;
    final editable = code.isNotEmpty && code != 'other';
    final action = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (editable) ListTile(leading: const Icon(Icons.edit_outlined), title: const Text('重命名'), onTap: () => Navigator.pop(context, 'rename')),
            for (final item in [('多选', Icons.checklist, 'select'), if (editable) ('合并到其他相册', Icons.merge, 'merge'), ('分享', Icons.share_outlined, 'share'), ('导出到文件夹', Icons.drive_file_move_outline, 'exportDirectory')])
              ListTile(leading: Icon(item.$2), title: Text(item.$1), onTap: () => Navigator.pop(context, item.$3)),
          ],
        ),
      ),
    );
    if (!mounted || action == null) return;
    if (action == 'rename') {
      await _renameAlbum(entry);
      return;
    }
    setState(() {
      selecting = true;
      selectedAlbums.add(code);
    });
    if (action != 'select') await _albumAction(action);
  }

  Future<void> _albumAction(String action) async {
    final codes = selectedAlbums.toList();
    if (action == 'merge') {
      if (codes.any((e) => e.isEmpty || e == 'other')) {
        _toast('全部截图和其他是系统相册，不能合并；请仅选择分类相册');
        return;
      }
      final targets = [
        {'code': 'other', 'name': '其他', 'count': gallery.stats['otherCount'] ?? 0},
        ...(gallery.stats['categories'] as List? ?? []).cast<Map>().where((e) => !codes.contains(e['code'])).map((e) => {...e, 'name': (e['name'] as String).isEmpty ? '未命名 · ${(e['code'] as String).substring(0, 12)}' : e['name']}),
      ];
      if (targets.isEmpty) {
        _toast('请保留一个未选中的目标相册');
        return;
      }
      final target = await showDialog<String>(
        context: context,
        builder: (_) => RhythmNameDialog(title: '合并到相册', initial: '', options: targets, chooseOnly: true),
      );
      if (target == null || !mounted) return;
      final destination = targets.firstWhere((e) => e['code'] == target);
      if (!await confirmRhythm(context, '确认合并？', '${codes.length} 个相册将合并到 ${destination['name']}（当前 ${destination['count']} 张）。可以在设置中撤销。')) return;
      await _run(() => gallery.action('mergeCategories', {'codes': codes, 'target': target}), success: '相册已合并');
      if (mounted) _exitSelection();
      return;
    }
    if (action == 'trash' && !await confirmRhythm(context, '移入回收站？', '所选相册内的全部照片将移入回收站，可恢复。系统原图不变。')) return;
    await _run(() async {
      final ids = await gallery.action('categoryIds', {'codes': codes}) as List;
      return gallery.action(['share', 'exportDirectory'].contains(action) ? action : 'batch', {'ids': ids, 'operation': action, if (action == 'favorite') 'value': true});
    }, success: '操作完成');
  }

  void _openCollection(String title, String category) {
    gallery.framePhases.mark('gallery_idle');
    setState(() {
      showingAlbums = false;
      collectionTitle = title;
    });
    _filter(() {
      gallery.category = category;
      gallery.achievement = '';
      gallery.album = '';
      gallery.favorite = false;
      gallery.trash = false;
    });
  }

  Widget _albumHome() {
    final categories = (gallery.stats['categories'] as List? ?? []).cast<Map>();
    final entries = <Map>[
      {'code': '', 'name': '全部截图', 'count': gallery.stats['count'] ?? 0, 'icon': Icons.photo_library_outlined,
        'songMissing': gallery.stats['songMissing'] ?? 0},
      ...categories,
      {'code': 'other', 'name': '其他', 'count': gallery.stats['otherCount'] ?? 0, 'icon': Icons.photo_outlined,
        'songMissing': gallery.stats['otherSongMissing'] ?? 0},
    ];
    return RefreshIndicator(
      onRefresh: gallery.refresh,
      child: AnimationLimiter(
        child: GridView.builder(
          key: const PageStorageKey('rhythm-albums'),
          controller: albumScroll,
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 100),
          physics: const AlwaysScrollableScrollPhysics(),
          gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(maxCrossAxisExtent: 240, childAspectRatio: .95, crossAxisSpacing: 12, mainAxisSpacing: 12),
          itemCount: entries.length,
          itemBuilder: (context, i) {
            final entry = entries[i];
            final code = entry['code'] as String;
            final name = (entry['name'] as String).isEmpty ? '未命名' : entry['name'] as String;
            final thumbnail = entry['thumbnail'] as String? ?? '';
            final card = RepaintBoundary(
              child: Card(
                key: ValueKey(code),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: busy
                      ? null
                      : () => selecting
                            ? setState(() {
                                if (!selectedAlbums.remove(code)) selectedAlbums.add(code);
                              })
                            : _openCollection(name, code),
                  onLongPress: busy ? null : () => _albumMenu(entry),
                  child: Stack(
                    children: [
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Expanded(
                            child: code.isEmpty || code == 'other'
                                ? _recentCover(other: code == 'other')
                                : thumbnail.isEmpty
                                ? Icon(entry['icon'] as IconData? ?? Icons.sports_esports_outlined, size: 52)
                                : Image.file(File(thumbnail), cacheWidth: 320, fit: BoxFit.cover, errorBuilder: (_, _, _) => const Icon(Icons.photo_library_outlined, size: 52)),
                          ),
                          ListTile(
                            dense: true,
                            contentPadding: EdgeInsets.only(left: 16, right: selecting ? 0 : 48),
                            title: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis),
                            subtitle: Text('${entry['count']} 张${name == '未命名' ? ' · ${code.substring(0, code.length.clamp(0, 12))}' : ''}'),
                            trailing: AnimatedSwitcher(
                              duration: MediaQuery.disableAnimationsOf(context) ? Duration.zero : const Duration(milliseconds: 160),
                              transitionBuilder: (child, animation) => ScaleTransition(scale: animation, child: child),
                              child: selecting
                                  ? Checkbox(
                                      value: selectedAlbums.contains(code),
                                      onChanged: busy
                                          ? null
                                          : (_) => setState(() {
                                              if (!selectedAlbums.remove(code)) selectedAlbums.add(code);
                                            }),
                                    )
                                  : const SizedBox(width: 0, height: 0),
                            ),
                          ),
                        ],
                      ),
                      if (code.isNotEmpty && code != 'other')
                        Positioned(
                          right: 0,
                          bottom: 0,
                          child: AnimatedSwitcher(
                            duration: MediaQuery.disableAnimationsOf(context) ? Duration.zero : const Duration(milliseconds: 160),
                            child: selecting ? const SizedBox() : IconButton(tooltip: '重命名 $name', icon: const Icon(Icons.edit_outlined, size: 20), onPressed: busy ? null : () => _renameAlbum(entry)),
                          ),
                        ),
                      if (gallery.stats['showSongMissing'] != false && (entry['songMissing'] as num? ?? 0) > 0)
                        Positioned(top: 6,right: 6,child: RhythmSongMissingBadge(count: entry['songMissing'] as num)),
                    ],
                  ),
                ),
              ),
            );
            if (MediaQuery.disableAnimationsOf(context)) return card;
            return AnimationConfiguration.staggeredGrid(
              key: ValueKey(code),
              position: i,
              columnCount: (MediaQuery.sizeOf(context).width / 252).ceil().clamp(1, 10),
              duration: const Duration(milliseconds: 200),
              delay: const Duration(milliseconds: 25),
              child: SlideAnimation(verticalOffset: 24, child: FadeInAnimation(child: card)),
            );
          },
        ),
      ),
    );
  }

  Widget _recentCover({bool other = false}) {
    final paths = (gallery.stats[other ? 'otherThumbnails' : 'recentThumbnails'] as List? ?? []).cast<String>();
    Widget cell(int i) => i < paths.length && paths[i].isNotEmpty
        ? Image.file(File(paths[i]), fit: BoxFit.cover, cacheWidth: 160, errorBuilder: (_, _, _) => const Icon(Icons.photo_outlined))
        : ColoredBox(color: Theme.of(context).colorScheme.surfaceContainerHighest, child: const Icon(Icons.photo_outlined));
    return Column(
      children: [
        for (var row = 0; row < 2; row++)
          Expanded(
            child: Row(
              children: [
                for (var column = 0; column < 2; column++)
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.all(1),
                      child: SizedBox.expand(child: cell(row * 2 + column)),
                    ),
                  ),
              ],
            ),
          ),
      ],
    );
  }

  Widget _chip(String title, bool value, VoidCallback pressed) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 4),
    child: ChoiceChip(label: Text(title), selected: value, onSelected: (_) => pressed()),
  );
  Widget _batchButton(String title, IconData icon, String operation) => TextButton.icon(onPressed: busy || selected.isEmpty ? null : () => _batch(operation), icon: Icon(icon), label: Text(title));
  Widget _tile(int index) {
    final row = gallery.items[index];
    final id = row['id'] as String;
    final thumb = row['thumbnail'] as String? ?? '';
    final isSelected = selected.contains(id);
    final achievement = rhythmAchievement(row);
    void toggle() => setState(() {
      selecting = true;
      if (!selected.remove(id)) selected.add(id);
    });
    return Semantics(
      label: row['name'] as String? ?? '截图',
      selected: isSelected,
      child: InkWell(
        onTap: busy ? null : () => selecting ? toggle() : _open(index),
        onLongPress: busy ? null : toggle,
        child: Stack(
          fit: StackFit.expand,
          children: [
            Hero(
              tag: 'rhythm-photo-$id',
              flightShuttleBuilder: rhythmThumbnailFlight(row),
              child: ColoredBox(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                child: thumb.isEmpty ? const Icon(Icons.hourglass_empty) : Image.file(File(thumb), cacheWidth: 320, fit: BoxFit.cover, errorBuilder: (_, _, _) => const Icon(Icons.broken_image_outlined)),
              ),
            ),
            IgnorePointer(
              child: AnimatedContainer(
                duration: MediaQuery.disableAnimationsOf(context) ? Duration.zero : const Duration(milliseconds: 160),
                color: Theme.of(context).colorScheme.primary.withValues(alpha: isSelected ? .3 : 0),
              ),
            ),
            if (achievement.isNotEmpty)
              Positioned(
                top: 4,
                left: 4,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(color: Colors.black87, borderRadius: BorderRadius.circular(6)),
                  child: Text(
                    achievement,
                    style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
                  ),
                ),
              ),
            Positioned(
              top: 4,
              right: 4,
              child: AnimatedSwitcher(
                duration: MediaQuery.disableAnimationsOf(context) ? Duration.zero : const Duration(milliseconds: 160),
                transitionBuilder: (child, animation) => ScaleTransition(scale: animation, child: child),
                child: selecting ? Icon(isSelected ? Icons.check_circle : Icons.radio_button_unchecked, key: ValueKey(isSelected), color: Colors.white) : const SizedBox(),
              ),
            ),
            if ((row['favorite'] as num? ?? 0) == 1) const Positioned(bottom: 5, left: 5, child: Icon(Icons.favorite, color: Colors.white, size: 18)),
            if (gallery.stats['showSongMissing'] != false && rhythmSongMissing(row))
              const Positioned(bottom: 5,right: 5,child: RhythmSongMissingBadge()),
            if (row['status'] == 'error') const Positioned(bottom: 38, right: 5, child: Icon(Icons.error_outline, color: Colors.orange)),
          ],
        ),
      ),
    );
  }
}
