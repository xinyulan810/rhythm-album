import 'dart:async';
import 'dart:collection';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'rhythm_motion.dart';

typedef RhythmPhoto = Map<String, dynamic>;

/// Decode at physical display resolution for fit-to-screen. Zoom requests the
/// original provider; stored files and exported pixels are never resized.
ImageProvider rhythmDisplayImage(BuildContext context, RhythmPhoto item, String path, {bool original = false}) {
  final file = FileImage(File(path));
  final width = (item['width'] as num? ?? 0).toDouble();
  final height = (item['height'] as num? ?? 0).toDouble();
  if (original || width <= 0 || height <= 0) return file;
  final size = MediaQuery.sizeOf(context);
  final pixelRatio = MediaQuery.devicePixelRatioOf(context);
  final widthRatio = size.width * pixelRatio / width;
  final heightRatio = size.height * pixelRatio / height;
  final ratio = (widthRatio < heightRatio ? widthRatio : heightRatio).clamp(0.0, 1.0);
  if (ratio >= 1) return file;
  return ResizeImage.resizeIfNeeded((width * ratio).ceil().clamp(1, width.toInt()), null, file);
}

/// Gallery boundary: backend paths can later become a region/raw-pixel provider.
class RhythmGalleryController extends ChangeNotifier {
  static const channel = MethodChannel('io.rhythmalbum/library');
  final List<RhythmPhoto> items = [];
  Map<String, dynamic> stats = {};
  String search = '';
  String album = '';
  String category = '';
  String achievement = '';
  bool favorite = false;
  bool trash = false;
  bool ascending = false;
  bool loading = false;
  bool more = true;
  String? error;
  int total = 0;
  int dataVersion = 0;
  bool viewerChanged = false;
  Stopwatch? openWatch;
  String? openedId;
  final framePhases = RhythmFramePhases();
  int _revision = 0;
  int _offset = 0;
  int _snapshot = DateTime.now().millisecondsSinceEpoch;
  bool _disposed = false;
  final _paths = <String, Future<String?>>{};
  final _prefetch = Queue<RhythmPhoto>();
  final _queued = <String>{};
  int _running = 0;
  int _prefetchGeneration = 0;

  Map<String, dynamic> get filter => {
    'search': search,
    'album': album,
    'category': category,
    'achievement': achievement,
    'favorite': favorite,
    'trash': trash,
    'ascending': ascending,
    'snapshot': _snapshot,
  };

  Future<void> refresh() async {
    _revision++;
    _offset = 0;
    _snapshot = DateTime.now().millisecondsSinceEpoch;
    more = true;
    await _load(reset: true);
  }

  Future<void> loadMore({int limit = 60}) async {
    if (loading || !more) return;
    await _load(reset: false, limit: limit);
  }

  Future<void> _load({required bool reset, int limit = 60}) async {
    final revision = _revision;
    loading = true;
    _notify();
    try {
      final data = await channel.invokeMapMethod<String, dynamic>('list', {
        ...filter,
        'offset': reset ? 0 : _offset,
        'limit': limit,
      });
      if (_disposed || revision != _revision) return;
      final rows = (data?['items'] as List? ?? []).map((e) => RhythmPhoto.from(e as Map)).toList();
      if (reset) items.clear();
      final existing = items.map((e) => e['id']).toSet();
      items.addAll(rows.where((e) => existing.add(e['id'])));
      dataVersion++;
      _offset = (reset ? 0 : _offset) + rows.length;
      total = (data?['total'] as num?)?.toInt() ?? items.length;
      more = rows.length == limit && _offset < total;
      stats = Map<String, dynamic>.from(data?['stats'] as Map? ?? {});
      _applyBudget();
      error = null;
    } catch (e) {
      if (revision == _revision) error = message(e);
    } finally {
      if (!_disposed && revision == _revision) {
        loading = false;
        _notify();
      }
    }
  }

  void _applyBudget() {
    final budget = stats['budget'] as Map? ?? {};
    PaintingBinding.instance.imageCache
      ..maximumSizeBytes = ((budget['cacheMb'] as num?)?.toInt() ?? 48) * 1024 * 1024
      ..maximumSize = 160;
  }

  Future<Object?> action(String method, [Map<String, dynamic>? arguments]) => channel.invokeMethod<Object?>(method, arguments);

  Future<String?> path(RhythmPhoto item, {bool retry = false}) {
    final id = item['id'] as String;
    if (retry) _paths.remove(id);
    final existing = _paths.remove(id);
    if (existing != null) {
      final checked = existing.then((value) async {
        if (value != null && await File(value).exists()) return value;
        return channel.invokeMethod<String>('view', {'id': id});
      });
      checked.ignore();
      _paths[id] = checked;
      return checked;
    }
    final future = channel.invokeMethod<String>('view', {'id': id});
    future.ignore();
    _paths[id] = future;
    while (_paths.length > 12) {
      _paths.remove(_paths.keys.first);
    }
    return future;
  }

  /// Nearby time-ordered images only. Generation cancels obsolete queued work.
  void prefetchNear(BuildContext context, int index) {
    if (_disposed || items.isEmpty) return;
    final budget = stats['budget'] as Map? ?? {};
    final radius = ((budget['prefetch'] as num?)?.toInt() ?? 1).clamp(0, 2);
    _prefetchGeneration++;
    _prefetch.clear();
    _queued.clear();
    for (final offset in [
      0,
      for (var i = 1; i <= radius; i++) ...[i, -i],
    ]) {
      final position = index + offset;
      if (position < 0 || position >= items.length) continue;
      final item = items[position];
      if (item['status'] == 'queued') continue;
      if (_queued.add(item['id'] as String)) _prefetch.add(item);
    }
    _drain(context);
  }

  void _drain(BuildContext context) {
    final budget = stats['budget'] as Map? ?? {};
    final maxJobs = ((budget['decodeJobs'] as num?)?.toInt() ?? 1).clamp(1, 2);
    while (!_disposed && context.mounted && _running < maxJobs && _prefetch.isNotEmpty) {
      final item = _prefetch.removeFirst();
      final generation = _prefetchGeneration;
      _running++;
      unawaited(() async {
        try {
          final file = await path(item);
          if (!_disposed && context.mounted && generation == _prefetchGeneration && file != null) {
            await precacheImage(rhythmDisplayImage(context, item, file), context, onError: (_, _) {});
          }
        } catch (_) {
          _paths.remove(item['id'])?.ignore();
        } finally {
          _running--;
          if (!_disposed && context.mounted) _drain(context);
        }
      }());
    }
  }

  void clearPrefetch() {
    _prefetchGeneration++;
    _prefetch.clear();
    PaintingBinding.instance.imageCache.clear();
  }

  static String message(Object error) => error is PlatformException ? error.message ?? error.code : '$error';
  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _prefetch.clear();
    _paths.clear();
    super.dispose();
  }
}
