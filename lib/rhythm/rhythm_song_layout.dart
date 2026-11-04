import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'rhythm_gallery_controller.dart';

class RhythmSongLayoutPage extends StatefulWidget {
  const new({super.key, required this.row, required this.imagePath});
  final RhythmPhoto row;
  final String imagePath;
  @override
  State<RhythmSongLayoutPage> createState() => _RhythmSongLayoutPageState();
}

class _RhythmSongLayoutPageState extends State<RhythmSongLayoutPage> {
  final title = TextEditingController();
  List<Map<String, dynamic>> lines = [];
  Rect? region;
  Offset? start;
  bool loading = true;
  bool saving = false;
  String? error;
  String code = '';
  double ratio = 1;
  @override
  void initState() {
    super.initState();
    code = widget.row['songLayoutKey'] as String? ?? '';
    title.text = widget.row['song'] as String? ?? '';
    scan();
  }
  @override
  void dispose() { title.dispose(); super.dispose(); }
  Map<String, double> coordinates(Rect rect) => {'x': rect.left, 'y': rect.top, 'right': rect.right, 'bottom': rect.bottom};
  Rect rectOf(Map value) => Rect.fromLTRB((value['x'] as num).toDouble(), (value['y'] as num).toDouble(),
      (value['right'] as num).toDouble(), (value['bottom'] as num).toDouble());

  Future<void> scan({bool selection = false}) async {
    setState(() { loading = true; error = null; });
    try {
      final result = await RhythmGalleryController.channel.invokeMapMethod<String, dynamic>('songLayoutScan', {
        'id': widget.row['id'], if (selection && region != null) 'region': coordinates(region!),
      });
      if (!mounted || result == null) return;
      setState(() {
        code = result['code'] as String;
        ratio = (result['width'] as num) / (result['height'] as num);
        if (selection) {
          title.text = result['text'] as String? ?? '';
        } else {
          lines = (result['lines'] as List? ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
          final saved = result['region'] as Map? ?? {};
          if (saved.isNotEmpty) region = rectOf(saved);
        }
      });
    } catch (e) { if (mounted) setState(() => error = RhythmGalleryController.message(e)); }
    finally { if (mounted) setState(() => loading = false); }
  }
  Future<void> save({bool reset = false}) async {
    setState(() { saving = true; error = null; });
    try {
      final row = await RhythmGalleryController.channel.invokeMapMethod<String, dynamic>('songLayoutSave', {
        'id': widget.row['id'],
        if (!reset && region != null) 'region': coordinates(region!),
        if (!reset) 'title': title.text.trim(),
      });
      if (mounted) Navigator.pop(context, row);
    } catch (e) { if (mounted) setState(() => error = RhythmGalleryController.message(e)); }
    finally { if (mounted) setState(() => saving = false); }
  }
  Offset normalized(Offset point, Size size) => Offset((point.dx / size.width).clamp(0, 1), (point.dy / size.height).clamp(0, 1));
  void choose(Offset point) {
    final choices = lines.where((line) => rectOf(line).inflate(.008).contains(point)).toList()
      ..sort((a,b) => (rectOf(a).width * rectOf(a).height).compareTo(rectOf(b).width * rectOf(b).height));
    if (choices.isEmpty) return;
    final box = rectOf(choices.first).inflate(.006).intersect(const Rect.fromLTRB(0,0,1,1));
    setState(() { region = box; title.clear(); });
    scan(selection: true);
  }
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('设置歌名位置')),
    body: ListView(padding: const EdgeInsets.all(16), children: [
      const Text('点选图中的歌名文字框，或拖动框选歌名区域，松手后扫描。位置按图片宽高比例保存。'),
      Text(code.isEmpty ? '正在识别画面布局…' : code.startsWith('image:')
          ? '尚未确定共享布局，位置仅绑定本图。'
          : '绑定识别布局：$code（相册改名、移动不会更改）'),
      const SizedBox(height: 12),
      SizedBox(height: MediaQuery.sizeOf(context).height * .46, child: LayoutBuilder(builder: (context, constraints) {
        final width = math.min(constraints.maxWidth, constraints.maxHeight * ratio);
        final size = Size(width, width / ratio);
        return Center(child: SizedBox.fromSize(size: size, child: GestureDetector(
          key: const Key('song-layout-canvas'),
          onTapUp: loading || saving ? null : (d) => choose(normalized(d.localPosition,size)),
          onPanStart: loading || saving ? null : (d) {
            start = normalized(d.localPosition,size);
            setState(() { region = null; title.clear(); });
          },
          onPanUpdate: loading || saving ? null : (d) {
            if (start != null) setState(() => region = Rect.fromPoints(start!,normalized(d.localPosition,size)));
          },
          onPanEnd: loading || saving ? null : (_) {
            start = null;
            if (region != null && region!.width >= .005 && region!.height >= .005) {
              scan(selection: true);
            } else { setState(() => region = null); }
          },
          child: Stack(fit: StackFit.expand, children: [
            Image.file(File(widget.imagePath), fit: BoxFit.fill, cacheWidth: 1600,
              errorBuilder: (_,_,_) => const Center(child: Text('图片加载失败'))),
            CustomPaint(painter: _SongRegions(lines.map(rectOf).toList(),region)),
            if (loading) const ColoredBox(color: Colors.black38, child: Center(child: CircularProgressIndicator())),
          ]),
        )));
      })),
      const SizedBox(height: 12),
      TextField(controller: title, maxLength: 300, decoration: const InputDecoration(labelText: '本图歌名（可纠正）',
        helperText: '歌名只确认本图；识别位置用于同一布局编码的图片。')),
      if (error != null) Text(error!,style: TextStyle(color: Theme.of(context).colorScheme.error)),
      Wrap(spacing: 8, children: [
        TextButton(onPressed: loading || saving || region == null ? null : () => scan(selection: true), child: const Text('扫描选区')),
        TextButton(onPressed: loading || saving ? null : scan, child: const Text('重扫文字框')),
        TextButton(onPressed: loading || saving || code.isEmpty ? null : () => save(reset: true), child: const Text('恢复默认位置')),
        FilledButton(onPressed: loading || saving || region == null || code.isEmpty ? null : save, child: Text(saving ? '保存中' : '保存位置与本图歌名')),
      ]),
      const Text('保存后会重识别同编码的自动结果，人工确认保留。不在曲库的歌名可保存为本地歌曲。'),
    ]),
  );
}

class _SongRegions extends CustomPainter {
  new(this.boxes,this.selected);
  final List<Rect> boxes;
  final Rect? selected;
  @override
  void paint(Canvas canvas,Size size) {
    Rect scaled(Rect r) => Rect.fromLTRB(r.left*size.width,r.top*size.height,r.right*size.width,r.bottom*size.height);
    final paint = Paint()..color=Colors.cyanAccent.withValues(alpha: .55)..style=PaintingStyle.stroke..strokeWidth=1;
    for (final box in boxes) { canvas.drawRect(scaled(box),paint); }
    if (selected != null) {
      canvas.drawRect(scaled(selected!),Paint()..color=Colors.amber.withValues(alpha: .2));
      canvas.drawRect(scaled(selected!),paint..color=Colors.amber..strokeWidth=3);
    }
  }
  @override
  bool shouldRepaint(covariant _SongRegions old) => old.selected != selected || old.boxes != boxes;
}
