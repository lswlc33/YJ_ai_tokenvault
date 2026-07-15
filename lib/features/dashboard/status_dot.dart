import 'package:flutter/material.dart';
import 'package:flutter/widget_previews.dart';

import '../../models/key_status.dart';

/// 状态灯（计划 §4.4）：🟢正常 🔴失效 🟡不足/未知 ⚪未探测。
class StatusDot extends StatelessWidget {
  const StatusDot(this.status, {super.key, this.size = 10, this.label = false});

  final KeyStatus status;
  final double size;
  final bool label;

  Color get _color {
    switch (status) {
      case KeyStatus.ok:
        return const Color(0xFF34C759);
      case KeyStatus.invalid:
        return const Color(0xFFFF3B30);
      case KeyStatus.insufficient:
        return const Color(0xFFFF9500);
      case KeyStatus.overdue:
        return const Color(0xFFFF3B30);
      case KeyStatus.unknown:
        return const Color(0xFF9E9E9E);
    }
  }

  String get _text {
    switch (status) {
      case KeyStatus.ok:
        return '正常';
      case KeyStatus.invalid:
        return '失效';
      case KeyStatus.insufficient:
        return '余额不足';
      case KeyStatus.overdue:
        return '欠费中';
      case KeyStatus.unknown:
        return '未探测';
    }
  }

  @override
  Widget build(BuildContext context) {
    final dot = Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: _color, shape: BoxShape.circle),
    );
    if (!label) return dot;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        dot,
        const SizedBox(width: 6),
        Text(_text, style: TextStyle(color: _color, fontSize: 12)),
      ],
    );
  }
}

@Preview(name: 'StatusDot - OK')
Widget previewStatusDotOk() {
  return const StatusDot(KeyStatus.ok, size: 12, label: true);
}

@Preview(name: 'StatusDot - Invalid')
Widget previewStatusDotInvalid() {
  return const StatusDot(KeyStatus.invalid, size: 12, label: true);
}

@Preview(name: 'StatusDot - Insufficient')
Widget previewStatusDotInsufficient() {
  return const StatusDot(KeyStatus.insufficient, size: 12, label: true);
}

@Preview(name: 'StatusDot - Overdue')
Widget previewStatusDotOverdue() {
  return const StatusDot(KeyStatus.overdue, size: 12, label: true);
}

@Preview(name: 'StatusDot - Unknown')
Widget previewStatusDotUnknown() {
  return const StatusDot(KeyStatus.unknown, size: 12, label: true);
}

@Preview(name: 'StatusDot - Dot Only')
Widget previewStatusDotNoLabel() {
  return const StatusDot(KeyStatus.ok);
}
