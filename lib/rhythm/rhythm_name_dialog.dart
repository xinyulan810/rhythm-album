import 'package:flutter/material.dart';

/// Editable combobox: opening shows every choice; typing filters immediately.
class RhythmNameDialog extends StatefulWidget {
  const new({super.key, required this.title, required this.initial, required this.options, this.correction = false, this.chooseOnly = false, this.helperText});
  final String title, initial;
  final List<Map> options;
  final bool correction, chooseOnly;
  final String? helperText;
  @override
  State<RhythmNameDialog> createState() => _RhythmNameDialogState();
}

class _RhythmNameDialogState extends State<RhythmNameDialog> {
  late final text = TextEditingController(text: widget.initial);
  String query = '';
  bool expanded = true;
  Map? choice;
  @override
  void dispose() {
    text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final options = widget.options.where((e) => (e['name'] as String).toLowerCase().contains(query.trim().toLowerCase())).toList();
    return AlertDialog(
      title: Text(widget.title),
      content: SizedBox(
        width: 400,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: text,
                maxLength: 100,
                onTap: () => setState(() {
                  if (expanded) return;
                  expanded = true;
                  query = '';
                }),
                onChanged: (value) => setState(() {
                  query = value;
                  expanded = true;
                  choice = null;
                }),
                decoration: InputDecoration(
                  labelText: '相册名称',
                  suffixIcon: IconButton(
                    tooltip: '展开相册名称',
                    icon: Icon(expanded ? Icons.arrow_drop_up : Icons.arrow_drop_down),
                    onPressed: () => setState(() {
                      expanded = !expanded;
                      query = '';
                    }),
                  ),
                ),
              ),
              if (expanded)
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 220),
                  child: Material(
                    color: Theme.of(context).colorScheme.surfaceContainer,
                    borderRadius: BorderRadius.circular(12),
                    clipBehavior: Clip.antiAlias,
                    child: options.isEmpty
                        ? Padding(padding: const EdgeInsets.all(16), child: Text(widget.chooseOnly ? '没有匹配相册' : '没有匹配名称，可输入新名称'))
                        : ListView.builder(
                            shrinkWrap: true,
                            itemCount: options.length,
                            itemBuilder: (context, index) {
                              final option = options[index];
                              return ListTile(
                                title: Text(option['name'] as String),
                                trailing: Text('${option['count'] ?? 0} 张', style: Theme.of(context).textTheme.bodySmall),
                                onTap: () => setState(() {
                                  choice = option;
                                  text.text = option['name'] as String;
                                  text.selection = TextSelection.collapsed(offset: text.text.length);
                                  expanded = false;
                                  FocusScope.of(context).unfocus();
                                }),
                              );
                            },
                          ),
                  ),
                ),
              const SizedBox(height: 12),
              Text(widget.helperText ?? (widget.chooseOnly ? '选择目标后将合并所选相册；可在设置中撤销。' : '与已有相册同名将自动合并\n${widget.correction ? '留空创建未命名分类；填写“其他”移出音游分类' : '留空显示未命名；可在设置中撤销命名或合并'}'), style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
        FilledButton(
          onPressed: widget.chooseOnly && choice == null ? null : () => Navigator.pop(context, widget.chooseOnly ? choice!['code'] : text.text.trim()),
          child: Text(widget.chooseOnly ? '选择目标' : '保存'),
        ),
      ],
    );
  }
}
