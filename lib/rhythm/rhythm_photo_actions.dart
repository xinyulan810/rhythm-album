import 'package:flutter/material.dart';

import 'rhythm_gallery_controller.dart';

String rhythmAchievement(RhythmPhoto row) => row['achievementOverride'] != null && row['achievementOverride'] != 'auto' ? row['achievementOverride'] as String : row['achievement'] as String? ?? '';

String rhythmDate(RhythmPhoto row, {bool time = false}) {
  final value = (row['taken'] as num?)?.toInt() ?? (row['created'] as num?)?.toInt() ?? 0;
  if (value == 0) return '日期未知';
  final date = DateTime.fromMillisecondsSinceEpoch(value);
  String two(int n) => n.toString().padLeft(2, '0');
  return '${date.year}-${two(date.month)}-${two(date.day)}${time ? ' ${two(date.hour)}:${two(date.minute)}' : ''}';
}

Future<bool> confirmRhythm(BuildContext context, String title, String content) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(content),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('确认')),
        ],
      ),
    ) ??
    false;

Future<String?> rhythmText(BuildContext context, String title, {String value = ''}) async {
  final controller = TextEditingController(text: value);
  final result = await showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title),
      content: TextField(controller: controller, autofocus: true, maxLength: 100),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
        FilledButton(onPressed: () => Navigator.pop(context, controller.text.trim()), child: const Text('保存')),
      ],
    ),
  );
  await Future<void>.delayed(const Duration(milliseconds: 250));
  controller.dispose();
  return result;
}

Future<Map<String, dynamic>?> editRhythmMetadata(BuildContext context, RhythmPhoto item) async {
  const fields = {'name': '文件名', 'game': '游戏', 'song': '曲名', 'album': '相册', 'tags': '标签'};
  final controllers = {for (final key in fields.keys) key: TextEditingController(text: item[key] as String? ?? '')};
  var eligible = (item['eligible'] as num? ?? 0) == 1;
  var achievement = item['achievementOverride'] as String? ?? 'auto';
  final result = await showDialog<Map<String, dynamic>>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, setLocal) => AlertDialog(
        title: const Text('整理截图'),
        content: SizedBox(
          width: 420,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final field in fields.entries)
                  TextField(
                    controller: controllers[field.key],
                    maxLength: field.key == 'tags' ? 1000 : 256,
                    decoration: InputDecoration(labelText: field.value, helperText: field.key == 'game' ? '与已有音游相册同名将自动归入该相册' : null),
                  ),
                CheckboxListTile(contentPadding: EdgeInsets.zero, value: eligible, title: const Text('确认是该游戏的结算截图'), subtitle: const Text('所有新图默认编码；此项允许尝试同游戏参考压缩。'), onChanged: (value) => setLocal(() => eligible = value ?? false)),
                DropdownButtonFormField<String>(
                  initialValue: achievement,
                  decoration: InputDecoration(labelText: '成绩标签', helperText: '自动识别：${(item['achievement'] as String? ?? '').isEmpty ? '未判定' : item['achievement']}；手动设置优先'),
                  items: const [
                    DropdownMenuItem(value: 'auto', child: Text('自动识别')),
                    DropdownMenuItem(value: 'AP', child: Text('AP · All Perfect / Pure Memory')),
                    DropdownMenuItem(value: 'FC', child: Text('FC · Full Combo')),
                    DropdownMenuItem(value: '', child: Text('无成绩标签')),
                  ],
                  onChanged: (value) => setLocal(() => achievement = value ?? 'auto'),
                ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
          FilledButton(
            onPressed: () {
              if (eligible && controllers['game']!.text.trim().isEmpty && (item['categoryCode'] as String? ?? '').isEmpty) {
                ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('确认结算图前请填写游戏')));
                return;
              }
              Navigator.pop(context, {'id': item['id'], 'eligible': eligible, 'achievementOverride': achievement, for (final field in controllers.entries) field.key: field.value.text.trim()});
            },
            child: const Text('保存'),
          ),
        ],
      ),
    ),
  );
  await Future<void>.delayed(const Duration(milliseconds: 250));
  for (final controller in controllers.values) {
    controller.dispose();
  }
  return result;
}

void showRhythmDetails(BuildContext context, RhythmPhoto row) {
  showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(row['name'] as String? ?? '', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 16),
              SelectableText(
                '时间：${rhythmDate(row, time: true)}\n'
                '尺寸：${row['width']} × ${row['height']}\n'
                '原文件：${((row['originalBytes'] as num? ?? 0) / 1024).toStringAsFixed(1)} KiB\n'
                '编码状态：${row['status']}\n'
                '存储包：${((row['storedBytes'] as num? ?? 0) / 1024).toStringAsFixed(1)} KiB\n'
                '游戏：${row['game'] ?? ''}\n类别编码：${row['categoryCode'] ?? ''}\n曲名：${row['song'] ?? ''}\n相册：${row['album'] ?? ''}\n标签：${row['tags'] ?? ''}',
              ),
              if ((row['error'] as String? ?? '').isNotEmpty) Text('处理信息：${row['error']}'),
              Text('成绩标签：${rhythmAchievement(row).isEmpty ? '未判定' : rhythmAchievement(row)}'),
              if ((row['ocrError'] as String? ?? '').isNotEmpty) Text('文字识别：${row['ocrError']}'),
              const SizedBox(height: 12),
              const Text('识别文字（中英日 · 离线）'),
              SelectableText((row['ocrText'] as String? ?? '').isEmpty ? '暂无识别文字' : row['ocrText'] as String),
              const SizedBox(height: 12),
              Text(row['sourceRemoved'] == 1 ? '已代管：系统原图已删除。清空数据或卸载前请先导出。' : '系统原图未由应用清理。'),
              if ((row['sourceDeleteError'] as String? ?? '').isNotEmpty) Text('原图清理：${row['sourceDeleteError']}'),
            ],
          ),
        ),
      ),
    ),
  );
}
