import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:aves_magnifier/aves_magnifier.dart';
import 'package:aves/widgets/viewer/image_gesture_pager.dart';

import 'rhythm_gallery_controller.dart';
import 'rhythm_photo_actions.dart';
import 'rhythm_editor.dart';
import 'rhythm_motion.dart';

class RhythmViewer extends StatefulWidget {
  const new({super.key, required this.gallery, required this.initialIndex, this.onPhotoChanged});
  final RhythmGalleryController gallery;
  final int initialIndex;
  final ValueChanged<int>? onPhotoChanged;
  @override
  State<RhythmViewer> createState() => _RhythmViewerState();
}

class _RhythmViewerState extends State<RhythmViewer> {
  late final PageController pages;
  final verticalPages = PageController(initialPage: 1);
  late int index;
  bool controls = true;
  bool zoomed = false;
  bool busy = false;
  bool paging = false, verticalMotion = false, routeReady = false, leaving = false;
  Animation<double>? routeAnimation;
  RhythmGalleryController get gallery => widget.gallery;
  RhythmPhoto? get current => index < gallery.items.length ? gallery.items[index] : null;
  @override
  void initState() {
    super.initState();
    index = widget.initialIndex;
    pages = PageController(initialPage: index);
    gallery.addListener(_changed);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final animation = ModalRoute.of(context)?.animation;
    if (animation != routeAnimation) {
      routeAnimation?.removeStatusListener(_routeStatus);
      routeAnimation = animation;
      animation?.addStatusListener(_routeStatus);
      routeReady = animation == null || animation.status == AnimationStatus.completed;
      if (routeReady) WidgetsBinding.instance.addPostFrameCallback((_) => _warm());
    }
  }

  void _routeStatus(AnimationStatus status) {
    if (!mounted) return;
    setState(() => routeReady = status == AnimationStatus.completed);
    if (routeReady) {
      gallery.framePhases.mark('viewer_idle');
      _warm();
    } else if (status == AnimationStatus.reverse) {
      gallery.framePhases.mark('viewer_return');
    }
  }

  void _leave() {
    if (leaving) return;
    leaving = true;
    gallery.framePhases.mark('viewer_return');
    widget.onPhotoChanged?.call(index);
    // Give the underlying list one layout frame to expose the Hero destination.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) Navigator.pop(context);
    });
    setState(() {});
  }

  void _changed() {
    if (mounted) {
      setState(() {});
      if (!gallery.loading) WidgetsBinding.instance.addPostFrameCallback((_) => _warm());
    }
  }

  void _toggleControls() {
    setState(() => controls = !controls);
    unawaited(SystemChrome.setEnabledSystemUIMode(controls ? SystemUiMode.edgeToEdge : SystemUiMode.immersiveSticky));
  }

  void _warm() {
    if (!mounted || !routeReady || verticalMotion || leaving) return;
    gallery.prefetchNear(context, index);
    if (index >= gallery.items.length - 4) unawaited(gallery.loadMore());
  }

  @override
  void dispose() {
    gallery.removeListener(_changed);
    routeAnimation?.removeStatusListener(_routeStatus);
    pages.dispose();
    verticalPages.dispose();
    unawaited(SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge));
    super.dispose();
  }

  void _toast(String message) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _action(String action) async {
    final item = current;
    if (item == null || busy) return;
    final id = item['id'] as String;
    try {
      if (action == 'details') {
        unawaited(verticalPages.animateToPage(2, duration: const Duration(milliseconds: 250), curve: Curves.easeInOutCubic));
        return;
      }
      if (action == 'metadata') {
        final data = await editRhythmMetadata(context, item);
        if (data == null) return;
        await gallery.action('edit', data);
        gallery.viewerChanged = true;
        if (mounted) setState(() => item.addAll(data));
        return;
      }
      if (action == 'editImage') {
        final path = await gallery.path(item);
        if (!mounted || path == null) return;
        final saved = await Navigator.push<bool>(
          context,
          MaterialPageRoute(
            builder: (_) => RhythmEditor(gallery: gallery, item: item, path: path),
          ),
        );
        if (saved == true) {
          gallery.viewerChanged = true;
          _toast('已另存一张编辑后的截图');
        }
        return;
      }
      if (action == 'trash' || action == 'purge') {
        if (!await confirmRhythm(context, action == 'trash' ? '移入回收站？' : '永久删除？', action == 'trash' ? '本相册中的照片可在回收站恢复。' : '删除本相册图片且不可恢复；已代管且未导出的图片将丢失。')) return;
      }
      if (!mounted) return;
      setState(() => busy = true);
      if (action == 'favorite') {
        final value = (item['favorite'] as num? ?? 0) != 1;
        await gallery.action('batch', {
          'ids': [id],
          'operation': 'favorite',
          'value': value,
        });
        item['favorite'] = value ? 1 : 0;
        gallery.viewerChanged = true;
      } else if (['trash', 'restore', 'purge'].contains(action)) {
        await gallery.action('batch', {
          'ids': [id],
          'operation': action,
        });
        gallery.viewerChanged = true;
        if (mounted) Navigator.pop(context);
      } else if (action == 'retry') {
        await gallery.action('retry', {'id': id});
        _toast('已重新加入处理队列');
      } else {
        final count = await gallery.action(action, {
          'ids': [id],
        });
        if (action != 'share') _toast('已导出 $count 张');
      }
    } catch (e) {
      _toast(RhythmGalleryController.message(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _move(int delta) async {
    final target = index + delta;
    if (target < 0) return;
    if (target >= gallery.items.length) await gallery.loadMore();
    if (mounted && target < gallery.items.length) {
      await pages.animateToPage(target, duration: const Duration(milliseconds: 250), curve: Curves.easeInOutCubic);
    }
  }

  @override
  Widget build(BuildContext context) {
    final item = current;
    return Theme(
      data: ThemeData.dark(useMaterial3: true),
      child: Scaffold(
        backgroundColor: Colors.transparent,
        body: AvesImageGesturePager(
          controller: verticalPages,
          onLeave: _leave,
          onMotion: (value) {
            if (verticalMotion != value && mounted) {
              verticalMotion = value;
              if (!leaving) gallery.framePhases.mark(value ? 'viewer_vertical' : 'viewer_idle');
              if (!value) _warm();
            }
          },
          details: SafeArea(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    IconButton(
                      tooltip: '返回照片',
                      icon: const Icon(Icons.keyboard_arrow_down),
                      onPressed: () => verticalPages.animateToPage(1, duration: const Duration(milliseconds: 250), curve: Curves.easeInOutCubic),
                    ),
                    const Text('照片详情'),
                  ],
                ),
                if (item != null)
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(24),
                      child: SelectableText(
                        '时间：${rhythmDate(item, time: true)}\n'
                        '尺寸：${item['width']} × ${item['height']}\n'
                        '游戏：${item['game'] ?? ''}\n曲名：${item['song'] ?? ''}\n'
                        '成绩标签：${rhythmAchievement(item).isEmpty ? '未判定' : rhythmAchievement(item)}\n\n'
                        '${item['ocrText'] ?? ''}',
                        style: const TextStyle(color: Colors.white, height: 1.5),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          image: Stack(
            children: [
              Positioned.fill(
                child: MagnifierGestureDetectorScope(
                  axis: const [Axis.horizontal, Axis.vertical],
                  escapeByFling: false,
                  child: NotificationListener<ScrollNotification>(
                    onNotification: (event) {
                      if (event.depth == 0) {
                        if (event is ScrollStartNotification && !paging) {
                          setState(() => paging = true);
                          if (routeReady && !leaving) gallery.framePhases.mark('viewer_horizontal');
                        }
                        if (event is ScrollEndNotification && paging) {
                          setState(() => paging = false);
                          if (routeReady && !leaving) gallery.framePhases.mark('viewer_idle');
                        }
                      }
                      return false;
                    },
                    child: PageView.builder(
                      key: const Key('rhythm-horizontal-pager'),
                      controller: pages,
                      physics: MagnifierScrollerPhysics(gestureSettings: MediaQuery.gestureSettingsOf(context), parent: const BouncingScrollPhysics()),
                      itemCount: gallery.items.length + (gallery.more ? 1 : 0),
                      onPageChanged: (value) {
                        setState(() {
                          index = value;
                          zoomed = false;
                        });
                        _warm();
                      },
                      itemBuilder: (context, position) {
                        if (position == gallery.items.length) {
                          return Center(
                            child: gallery.error == null ? const CircularProgressIndicator() : TextButton(onPressed: gallery.loadMore, child: Text('加载失败，点击重试\n${gallery.error}')),
                          );
                        }
                        final row = gallery.items[position];
                        return ClipRect(
                          child: RhythmImagePage(
                            key: ValueKey(row['id']),
                            gallery: gallery,
                            item: row,
                            active: position == index,
                            moving: paging,
                            fullResolution: true,
                            toggleControls: _toggleControls,
                            onZoom: (value) {
                              if (position == index && mounted && zoomed != value) setState(() => zoomed = value);
                            },
                          ),
                        );
                      },
                    ),
                  ),
                ),
              ),
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                child: RhythmViewerChrome(
                  visible: controls,
                  top: true,
                  child: ColoredBox(
                    color: Colors.black54,
                    child: SafeArea(
                      bottom: false,
                      child: Row(
                        children: [
                          BackButton(onPressed: _leave),
                          Expanded(
                            child: Text('${index + 1} / ${gallery.total}\n${item == null ? '' : rhythmDate(item, time: true)}', maxLines: 2, style: const TextStyle(color: Colors.white)),
                          ),
                          IconButton(tooltip: '详情', onPressed: () => _action('details'), icon: const Icon(Icons.info_outline)),
                          PopupMenuButton<String>(
                            onSelected: _action,
                            itemBuilder: (_) => [
                              if (!gallery.trash) ...[
                                const PopupMenuItem(value: 'metadata', child: Text('整理 / 改名')),
                                const PopupMenuItem(value: 'editImage', child: Text('裁剪 / 旋转')),
                                const PopupMenuItem(value: 'export', child: Text('保存到系统相册')),
                                const PopupMenuItem(value: 'exportDirectory', child: Text('导出到文件夹')),
                                if (item?['status'] == 'error') const PopupMenuItem(value: 'retry', child: Text('重试编码')),
                              ] else
                                const PopupMenuItem(value: 'purge', child: Text('永久删除')),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              Positioned(
                bottom: 0,
                left: 0,
                right: 0,
                child: RhythmViewerChrome(
                  visible: controls,
                  top: false,
                  child: ColoredBox(
                    color: Colors.black54,
                    child: SafeArea(
                      top: false,
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                        children: [
                          IconButton(tooltip: '上一张', onPressed: index > 0 ? () => _move(-1) : null, icon: const Icon(Icons.chevron_left)),
                          if (!gallery.trash) ...[
                            IconButton(tooltip: '收藏', onPressed: busy ? null : () => _action('favorite'), icon: Icon((item?['favorite'] as num? ?? 0) == 1 ? Icons.favorite : Icons.favorite_border)),
                            IconButton(tooltip: '分享', onPressed: busy ? null : () => _action('share'), icon: const Icon(Icons.share_outlined)),
                            IconButton(tooltip: '删除', onPressed: busy ? null : () => _action('trash'), icon: const Icon(Icons.delete_outline)),
                          ] else
                            IconButton(tooltip: '恢复', onPressed: busy ? null : () => _action('restore'), icon: const Icon(Icons.restore)),
                          IconButton(tooltip: '下一张', onPressed: index + 1 < gallery.total ? () => _move(1) : null, icon: const Icon(Icons.chevron_right)),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              if (busy)
                const Positioned.fill(
                  child: ColoredBox(
                    color: Colors.black38,
                    child: Center(child: CircularProgressIndicator()),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class RhythmImagePage extends StatefulWidget {
  const new({super.key, required this.gallery, required this.item, required this.active, required this.toggleControls, required this.onZoom, this.moving = false, this.fullResolution = true});
  final RhythmGalleryController gallery;
  final RhythmPhoto item;
  final bool active;
  final bool moving, fullResolution;
  final VoidCallback toggleControls;
  final ValueChanged<bool> onZoom;
  @override
  State<RhythmImagePage> createState() => _RhythmImagePageState();
}

class _RhythmImagePageState extends State<RhythmImagePage> {
  var magnifier = AvesMagnifierController();
  final watch = Stopwatch()..start();
  Future<String?>? path;
  bool reported = false;
  bool original = false;
  StreamSubscription? scaleSubscription;
  @override
  void initState() {
    super.initState();
    // Keep the decoded layer mounted during vertical motion and route changes.
    path = widget.gallery.path(widget.item);
    _listenScale();
  }

  void _listenScale() {
    scaleSubscription = magnifier.stateStream.listen((state) {
      final initial = magnifier.scaleBoundaries?.initialScale;
      if (!mounted || initial == null) return;
      final zoomed = (state.scale ?? initial) > initial * 1.02;
      // Upgrade once per visit. Do not repeatedly decode when pinch crosses
      // the fit scale or replace a ready original while returning.
      if (zoomed && !original) setState(() => original = true);
      widget.onZoom(zoomed);
    });
  }

  @override
  void didUpdateWidget(covariant RhythmImagePage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.fullResolution && path == null) path = widget.gallery.path(widget.item);
    if (!widget.active && !widget.moving && (oldWidget.active || oldWidget.moving)) {
      // reset() restores a null scale, but unchanged layout boundaries do not
      // initialize it again. Replace gesture state too, stopping old animations.
      scaleSubscription?.cancel();
      magnifier.dispose();
      magnifier = AvesMagnifierController();
      original = false;
      _listenScale();
    }
  }

  @override
  void dispose() {
    scaleSubscription?.cancel();
    magnifier.dispose();
    super.dispose();
  }

  void _frame() {
    if (reported) return;
    reported = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        unawaited(
          widget.gallery
              .action('viewTiming', {
                'id': widget.item['id'],
                'ms': watch.elapsedMilliseconds,
                if (widget.gallery.openedId == widget.item['id']) 'clickToClearMs': widget.gallery.openWatch?.elapsedMilliseconds,
                'frame': 'display',
              })
              .catchError((Object _) => null),
        );
      }
    });
  }

  Widget _retry(Object? error) => Center(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('暂时无法显示\n${error == null ? '' : RhythmGalleryController.message(error)}', textAlign: TextAlign.center),
        TextButton(
          onPressed: () => setState(() {
            reported = false;
            watch.reset();
            path = widget.gallery.path(widget.item, retry: true);
          }),
          child: const Text('重试'),
        ),
      ],
    ),
  );
  @override
  Widget build(BuildContext context) => HeroMode(
    enabled: widget.active,
    child: Hero(
      tag: 'rhythm-photo-${widget.item['id']}',
      transitionOnUserGestures: true,
      flightShuttleBuilder: rhythmThumbnailFlight(widget.item, gallery: widget.gallery),
      child: RepaintBoundary(
        child: AvesMagnifier(
          key: ObjectKey(magnifier),
          controller: magnifier,
          contentSize: Size(
            (widget.item['width'] as num? ?? 1080).toDouble().clamp(1, 8192),
            (widget.item['height'] as num? ?? 1920).toDouble().clamp(1, 8192),
          ),
          minScale: const ScaleLevel(ref: ScaleReference.contained),
          maxScale: const ScaleLevel(factor: 8),
          onTap: (_, _, _, _) => widget.toggleControls(),
          child: RepaintBoundary(
            child: SizedBox.expand(
              child: FutureBuilder<String?>(
                future: path,
                builder: (context, snapshot) {
                  if (snapshot.hasError) return _retry(snapshot.error);
                  final thumb = widget.item['thumbnail'] as String? ?? '';
                  return Stack(
                    fit: StackFit.expand,
                    alignment: Alignment.center,
                    children: [
                      if (thumb.isNotEmpty) Image.file(File(thumb), fit: BoxFit.contain, cacheWidth: 320, errorBuilder: (_, _, _) => const SizedBox()),
                      if (snapshot.hasData && widget.fullResolution)
                        Image(
                          image: rhythmDisplayImage(context, widget.item, snapshot.data!, original: original),
                          fit: BoxFit.contain,
                          gaplessPlayback: true,
                          frameBuilder: (_, child, frame, synchronous) {
                            if (frame != null || synchronous) {
                              _frame();
                            }
                            return child;
                          },
                          errorBuilder: (_, error, _) => _retry(error),
                        )
                      else if (thumb.isEmpty)
                        const CircularProgressIndicator(),
                    ],
                  );
                },
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

HeroFlightShuttleBuilder rhythmThumbnailFlight(RhythmPhoto item, {RhythmGalleryController? gallery}) => (context, animation, direction, from, to) {
  final thumb = item['thumbnail'] as String? ?? '';
  final fallback = thumb.isEmpty ? const SizedBox() : Image.file(File(thumb), fit: BoxFit.contain, cacheWidth: 320, errorBuilder: (_, _, _) => const SizedBox());
  // The full image shares the viewer's ImageCache entry. Upgrade the shuttle
  // when ready instead of enforcing a thumbnail-only 240ms wait.
  if (gallery == null) return fallback;
  return RepaintBoundary(
    child: FutureBuilder<String?>(
      future: gallery.path(item),
      builder: (context, snapshot) => snapshot.hasData
          ? Image(
              image: rhythmDisplayImage(context, item, snapshot.data!),
              fit: BoxFit.contain,
              frameBuilder: (_, child, frame, synchronous) => frame != null || synchronous ? child : fallback,
              errorBuilder: (_, _, _) => fallback,
            )
          : fallback,
    ),
  );
};
