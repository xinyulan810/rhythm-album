import 'dart:io';

import 'package:flutter/material.dart';

import 'rhythm_gallery_controller.dart';

class RhythmEditor extends StatefulWidget {
  const new({super.key, required this.gallery, required this.item, required this.path});
  final RhythmGalleryController gallery;
  final RhythmPhoto item;
  final String path;
  @override
  State<RhythmEditor> createState() => _RhythmEditorState();
}

class _RhythmEditorState extends State<RhythmEditor> {
  Rect crop = const Rect.fromLTRB(0, 0, 1, 1);
  Offset? start;
  int turns = 0;
  bool busy = false;
  Future<void> _save() async {
    setState(() => busy = true);
    try {
      await widget.gallery.action('editImage', {
        'id': widget.item['id'],
        'turns': turns,
        'crop': [crop.left, crop.top, crop.right, crop.bottom],
      });
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(RhythmGalleryController.message(e))));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('裁剪 / 旋转'),
      actions: [
        TextButton(onPressed: busy ? null : _save, child: const Text('另存副本')),
      ],
    ),
    body: Column(
      children: [
        const Padding(padding: EdgeInsets.all(12), child: Text('在图片上拖出裁剪区域。编辑会另存副本，原图不变。')),
        if (busy) const LinearProgressIndicator(),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Center(
              child: RotatedBox(
                quarterTurns: turns,
                child: AspectRatio(
                  aspectRatio: ((widget.item['width'] as num?)?.toDouble() ?? 1) / ((widget.item['height'] as num?)?.toDouble() ?? 1).clamp(1, double.infinity),
                  child: LayoutBuilder(
                    builder: (context, box) {
                      Offset normal(Offset point) => Offset((point.dx / box.maxWidth).clamp(0, 1), (point.dy / box.maxHeight).clamp(0, 1));
                      return GestureDetector(
                        onPanStart: busy ? null : (details) => start = normal(details.localPosition),
                        onPanUpdate: busy
                            ? null
                            : (details) {
                                final origin = start;
                                if (origin == null) return;
                                final end = normal(details.localPosition);
                                final next = Rect.fromPoints(origin, end);
                                if (next.width > .02 && next.height > .02) setState(() => crop = next);
                              },
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            Image.file(File(widget.path), fit: BoxFit.fill),
                            CustomPaint(painter: _CropPainter(crop)),
                          ],
                        ),
                      );
                    },
                  ),
                ),
              ),
            ),
          ),
        ),
        SafeArea(
          top: false,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              TextButton.icon(onPressed: busy ? null : () => setState(() => turns = (turns + 1) % 4), icon: const Icon(Icons.rotate_right), label: const Text('旋转 90°')),
              TextButton.icon(
                onPressed: busy
                    ? null
                    : () => setState(() {
                        crop = const Rect.fromLTRB(0, 0, 1, 1);
                        turns = 0;
                      }),
                icon: const Icon(Icons.restart_alt),
                label: const Text('重置'),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _CropPainter extends CustomPainter {
  new(this.crop);
  final Rect crop;
  @override
  void paint(Canvas canvas, Size size) {
    final rect = Rect.fromLTRB(crop.left * size.width, crop.top * size.height, crop.right * size.width, crop.bottom * size.height);
    final mask = Path()
      ..fillType = PathFillType.evenOdd
      ..addRect(Offset.zero & size)
      ..addRect(rect);
    canvas.drawPath(mask, Paint()..color = Colors.black54);
    canvas.drawRect(
      rect,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2,
    );
    final grid = Paint()
      ..color = Colors.white54
      ..strokeWidth = 1;
    for (var i = 1; i < 3; i++) {
      canvas.drawLine(Offset(rect.left + rect.width * i / 3, rect.top), Offset(rect.left + rect.width * i / 3, rect.bottom), grid);
      canvas.drawLine(Offset(rect.left, rect.top + rect.height * i / 3), Offset(rect.right, rect.top + rect.height * i / 3), grid);
    }
  }

  @override
  bool shouldRepaint(_CropPainter oldDelegate) => crop != oldDelegate.crop;
}
