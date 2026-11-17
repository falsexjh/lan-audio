import 'package:flutter/material.dart';

import '../audio_console_theme.dart';

/// Takes an explicit snapshot only when opened or refreshed.
class DebugInfoCard extends StatefulWidget {
  const DebugInfoCard({super.key, required this.isZh, required this.load});
  final bool isZh;
  final ValueGetter<Map<String, String>> load;

  @override
  State<DebugInfoCard> createState() => _DebugInfoCardState();
}

class _DebugInfoCardState extends State<DebugInfoCard> {
  bool _expanded = false;
  Map<String, String> _data = const {};

  @override
  void didUpdateWidget(covariant DebugInfoCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_expanded && oldWidget.isZh != widget.isZh) _data = widget.load();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AudioConsoleColors.bg2,
        borderRadius: AudioConsoleRadius.card,
      ),
      child: ExpansionTile(
        title: Text(
          widget.isZh ? '调试信息' : 'Debug Info',
          style: AudioConsoleType.body(color: AudioConsoleColors.text2),
        ),
        iconColor: AudioConsoleColors.text2,
        collapsedIconColor: AudioConsoleColors.text2,
        onExpansionChanged: (expanded) => setState(() {
          _expanded = expanded;
          _data = expanded ? widget.load() : const {};
        }),
        childrenPadding: const EdgeInsets.all(AudioConsoleSpacing.md),
        children: _expanded
            ? [
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    onPressed: () => setState(() => _data = widget.load()),
                    icon: const Icon(Icons.refresh, size: 16),
                    label: Text(widget.isZh ? '刷新' : 'Refresh'),
                  ),
                ),
                for (final entry in _data.entries)
                  Padding(
                    padding: const EdgeInsets.only(
                      bottom: AudioConsoleSpacing.xxs,
                    ),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: 120,
                          child: Text(
                            entry.key,
                            style: AudioConsoleType.monoMeta(),
                          ),
                        ),
                        Expanded(
                          child: Text(
                            entry.value,
                            style: AudioConsoleType.monoMeta(),
                          ),
                        ),
                      ],
                    ),
                  ),
              ]
            : const [],
      ),
    );
  }
}
