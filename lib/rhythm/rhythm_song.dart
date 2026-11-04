import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:aves/widgets/viewer/info/common.dart';
import 'rhythm_gallery_controller.dart';
import 'rhythm_viewer.dart';
import 'rhythm_photo_actions.dart';

class RhythmSongProgress extends StatelessWidget {
  const new({super.key, required this.progress});
  final Map<String, dynamic> progress;
  @override
  Widget build(BuildContext context) {
    final total = (progress['total'] as num? ?? 0).toInt();
    if (total == 0) return const SizedBox.shrink();
    final done = (progress['completed'] as num? ?? 0).toInt();
    final failed = (progress['failed'] as num? ?? 0).toInt();
    final waiting = (progress['waiting'] as num? ?? 0).toInt();
    final remaining = (total - done).clamp(0, total);
    final state = remaining == 0 ? '处理完成' : progress['paused'] == true ? '已暂停' : waiting == remaining ? '等待图片处理' : '处理中';
    return ListTile(
      key: const Key('rhythm-song-progress'),
      dense: true,
      leading: const Icon(Icons.music_note_outlined),
      title: Text('曲名识别 · $state · $done / $total'),
      subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('已关联 ${progress['matched'] ?? 0} 张 · 待处理 $remaining 张 · 失败 $failed 张'
          '${(progress['parallel'] as num? ?? 0) > 1 ? ' · ${progress['parallel']} 路并行' : ''}'),
        if (remaining > 0) ...[
          const SizedBox(height: 4),
          LinearProgressIndicator(value: (done / total).clamp(0.0, 1.0)),
        ],
      ]),
    );
  }
}

/// Uses the Aves details section divider; identity comes from the song catalogue.
class RhythmSongRelated extends StatefulWidget {
  const new({super.key, required this.row, required this.onReturn, required this.onConfirm});
  final RhythmPhoto row;
  final Future<void> Function() onReturn;
  final VoidCallback onConfirm;
  @override
  State<RhythmSongRelated> createState() => _RhythmSongRelatedState();
}

class _RhythmSongRelatedState extends State<RhythmSongRelated> {
  final gallery = RhythmGalleryController();
  bool opening = false;
  bool get hasTitle => (widget.row['songId'] as num? ?? 0) > 0 ||
      (widget.row['song'] as String? ?? '').trim().isNotEmpty || (widget.row['songRaw'] as String? ?? '').trim().isNotEmpty;
  @override
  void initState() {
    super.initState();
    gallery.relatedTo = widget.row['id'] as String;
    gallery.excludeId = widget.row['id'] as String;
    gallery.addListener(changed);
    if (hasTitle) gallery.refresh();
  }
  void changed() { if (mounted) setState(() {}); }
  @override
  void dispose() { gallery.removeListener(changed); gallery.dispose(); super.dispose(); }
  Future<void> open(int index) async {
    if (opening) return;
    setState(() => opening = true);
    try {
      await Navigator.push<void>(context, MaterialPageRoute(builder: (_) => RhythmViewer(gallery: gallery, initialIndex: index)));
      await widget.onReturn();
      if (mounted) await gallery.refresh();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(RhythmGalleryController.message(e))));
    } finally { if (mounted) setState(() => opening = false); }
  }
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const SectionRow(icon: Icons.photo_library_outlined),
      Text('同曲截图${hasTitle ? ' · ${gallery.total} 张' : ''}', style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      if (!hasTitle)
        TextButton(onPressed: widget.onConfirm, child: const Text('尚未识别到曲名，可手动填写'))
      else if (gallery.error != null)
        TextButton(onPressed: gallery.refresh, child: const Text('加载失败，点击重试'))
      else if (!gallery.loading && gallery.items.isEmpty)
        const Text('暂未找到同曲或曲名相似的截图')
      else
        SizedBox(height: 126, child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: gallery.items.length + (gallery.more ? 1 : 0),
          separatorBuilder: (_, _) => const SizedBox(width: 8),
          itemBuilder: (context, index) {
            if (index == gallery.items.length) {
              return TextButton(onPressed: gallery.loading ? null : gallery.loadMore, child: Text(gallery.loading ? '加载中' : '加载更多'));
            }
            final row = gallery.items[index];
            final path = row['thumbnail'] as String? ?? '';
            return SizedBox(width: 144, child: InkWell(
              key: ValueKey('related-${row['id']}'),
              onTap: opening ? null : () => open(index),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Expanded(child: ClipRRect(borderRadius: BorderRadius.circular(8), child:
                  path.isEmpty ? const ColoredBox(color: Colors.white12, child: Icon(Icons.image_outlined)) :
                  Image.file(File(path), fit: BoxFit.cover, cacheWidth: 320, errorBuilder: (_, _, _) => const Icon(Icons.broken_image_outlined)))),
                const SizedBox(height: 4),
                Text(rhythmDate(row, time: true), maxLines: 1, style: Theme.of(context).textTheme.labelSmall),
                Text((widget.row['songId'] as num? ?? 0) > 0 && row['songId'] == widget.row['songId'] ? '已关联同曲' : '曲名文字相似',
                  style: Theme.of(context).textTheme.labelSmall),
              ]),
            ));
          },
        )),
      if (hasTitle) const Padding(
        padding: EdgeInsets.only(top: 8),
        child: Text('同时查找曲名相似的截图，可能有误匹配；最多展示 400 张。', style: TextStyle(fontSize: 12, color: Colors.white70))),
      const SizedBox(height: 16),
    ],
  );
}

String rhythmSongState(RhythmPhoto row) => switch (row['songState']) {
  'matched' => '已关联曲库',
  'manual' => '已人工确认',
  'candidate' => '有候选，待确认',
  'unmatched' => '未匹配，保留原文',
  'not_applicable' => '未识别为支持的成绩页 / 已排除',
  'error' => '识别失败',
  _ => '等待识别',
};

Future<Map<String, dynamic>?> editRhythmSong(BuildContext context, RhythmPhoto row) =>
    showDialog<Map<String, dynamic>>(context: context, builder: (_) => _SongDialog(row: row));

class _SongDialog extends StatefulWidget {
  const new({required this.row});
  final RhythmPhoto row;
  @override
  State<_SongDialog> createState() => _SongDialogState();
}

class _SongDialogState extends State<_SongDialog> {
  late final TextEditingController title;
  late final TextEditingController artist;
  final aliases = TextEditingController();
  List<Map<String, dynamic>> candidates = [];
  late int selected;
  bool searching = false;
  String? error;
  @override
  void initState() {
    super.initState();
    title = TextEditingController(text: widget.row['song'] as String? ?? '');
    artist = TextEditingController(text: widget.row['songArtist'] as String? ?? '');
    selected = (widget.row['songId'] as num? ?? 0).toInt();
    try {
      candidates = (jsonDecode(widget.row['songCandidates'] as String? ?? '[]') as List)
          .map((e) => Map<String, dynamic>.from(e as Map)).toList();
    } catch (_) { candidates = []; }
  }

  @override
  void dispose() {
    title.dispose(); artist.dispose(); aliases.dispose();
    super.dispose();
  }

  Future<void> search() async {
    setState(() { searching = true; error = null; });
    try {
      final result = await RhythmGalleryController.channel.invokeListMethod<dynamic>('songSearch', {'query': title.text});
      if (mounted) setState(() => candidates = (result ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList());
    } catch (e) {
      if (mounted) setState(() => error = RhythmGalleryController.message(e));
    } finally {
      if (mounted) setState(() => searching = false);
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('确认曲名'),
    content: SizedBox(width: 440, child: SingleChildScrollView(child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(rhythmSongState(widget.row)),
        if ((widget.row['songRaw'] as String? ?? '').isNotEmpty)
          SelectableText('OCR 原文：${widget.row['songRaw']}'),
        if ((widget.row['songError'] as String? ?? '').isNotEmpty) Text('${widget.row['songError']}'),
        TextField(controller: title, maxLength: 300, decoration: const InputDecoration(labelText: '歌名或别名'),
          onChanged: (_) => setState(() => selected = 0)),
        TextField(controller: artist, maxLength: 300, decoration: const InputDecoration(labelText: '曲师（可选）'),
          onChanged: (_) => setState(() => selected = 0)),
        TextButton.icon(onPressed: searching ? null : search, icon: const Icon(Icons.search), label: Text(searching ? '查询中' : '查找曲库')),
        if (error != null) Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        for (final candidate in candidates)
          ListTile(
            dense: true, contentPadding: EdgeInsets.zero,
            leading: Icon(selected == candidate['id'] ? Icons.radio_button_checked : Icons.radio_button_off),
            title: Text(candidate['title'] as String),
            subtitle: Text(candidate['artist'] as String? ?? ''),
            onTap: () => setState(() {
              selected = (candidate['id'] as num).toInt();
              title.text = candidate['title'] as String;
              artist.text = candidate['artist'] as String? ?? '';
            }),
          ),
        TextField(controller: aliases, maxLength: 1000,
          decoration: const InputDecoration(labelText: '添加个人别名（每行一个）'), minLines: 1, maxLines: 3),
        Text(selected > 0 ? '将关联所选歌曲。歌曲相同不代表谱面相同。' : '未选中曲库项：确认后保存为本地歌曲，支持自制曲。'),
        const SizedBox(height: 8),
        const Text('曲名与别名数据：Arcaea Toolbelt · 6.16.1c 快照。未收录不代表自制谱。'),
      ],
    ))),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context, {'id': widget.row['id'], 'disabled': true}), child: const Text('不识别此图')),
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
      FilledButton(onPressed: title.text.trim().isEmpty ? null : () => Navigator.pop(context, {
        'id': widget.row['id'], 'songId': selected, 'title': title.text.trim(), 'artist': artist.text.trim(),
        'aliases': aliases.text.split('\n').map((e) => e.trim()).where((e) => e.isNotEmpty).take(30).toList(),
      }), child: const Text('确认')),
    ],
  );
}

/// A separate controller avoids replacing the underlying viewer's photo list.
class RhythmSongPage extends StatefulWidget {
  const new({super.key, required this.songId, required this.title, this.relatedTo = ''});
  final int songId;
  final String relatedTo;
  final String title;
  @override
  State<RhythmSongPage> createState() => _RhythmSongPageState();
}

class _RhythmSongPageState extends State<RhythmSongPage> {
  final gallery = RhythmGalleryController();
  @override
  void initState() {
    super.initState();
    gallery.songId = widget.relatedTo.isEmpty ? widget.songId : 0;
    gallery.relatedTo = widget.relatedTo;
    gallery.addListener(changed);
    gallery.refresh();
  }
  void changed() { if (mounted) setState(() {}); }
  @override
  void dispose() { gallery.removeListener(changed); gallery.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(widget.title)),
    body: Column(children: [
      Padding(padding: const EdgeInsets.all(12), child: Text('同曲及曲名相似截图 ${gallery.total} 张 · 可能误匹配，最多 400 张')),
      if (gallery.error != null) Text(gallery.error!),
      Expanded(child: NotificationListener<ScrollNotification>(
        onNotification: (n) { if (n.metrics.extentAfter < 400) gallery.loadMore(); return false; },
        child: GridView.builder(
          gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(maxCrossAxisExtent: 180, crossAxisSpacing: 4, mainAxisSpacing: 4),
          itemCount: gallery.items.length,
          itemBuilder: (context, index) {
            final item = gallery.items[index];
            final path = item['thumbnail'] as String? ?? '';
            return InkWell(onTap: () async {
              await Navigator.push<void>(context, MaterialPageRoute(builder: (_) => RhythmViewer(gallery: gallery, initialIndex: index)));
              if (mounted) await gallery.refresh();
            }, child: path.isEmpty ? const Icon(Icons.image_outlined) :
              Image.file(File(path), fit: BoxFit.cover, cacheWidth: 360, errorBuilder: (_, _, _) => const Icon(Icons.broken_image_outlined)));
          },
        ),
      )),
      if (gallery.loading) const LinearProgressIndicator(),
    ]),
  );
}
