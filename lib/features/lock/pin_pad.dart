import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 6 位 PIN 输入组件：圆点指示 + 数字键盘（桌面也可键盘直接打字）。
class PinPad extends StatefulWidget {
  const PinPad({
    super.key,
    required this.onCompleted,
    this.length = 6,
    this.error,
    this.busy = false,
  });

  final int length;
  final ValueChanged<String> onCompleted;
  final String? error;
  final bool busy;

  @override
  State<PinPad> createState() => _PinPadState();
}

class _PinPadState extends State<PinPad> {
  String _value = '';

  void _append(String digit) {
    if (widget.busy || _value.length >= widget.length) return;
    setState(() => _value += digit);
    if (_value.length == widget.length) {
      widget.onCompleted(_value);
      setState(() => _value = '');
    }
  }

  void _backspace() {
    if (_value.isEmpty) return;
    setState(() => _value = _value.substring(0, _value.length - 1));
  }

  /// 供外部在校验失败后清空。
  void reset() => setState(() => _value = '');

  @override
  void didUpdateWidget(covariant PinPad old) {
    super.didUpdateWidget(old);
    // 出现新错误时自动清空，便于重输。
    if (widget.error != null && widget.error != old.error) {
      _value = '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Focus(
      autofocus: true,
      onKeyEvent: (node, event) {
        if (event is KeyDownEvent) {
          final k = event.logicalKey;
          if (k == LogicalKeyboardKey.backspace) {
            _backspace();
            return KeyEventResult.handled;
          }
          final label = event.character;
          if (label != null && RegExp(r'^[0-9]$').hasMatch(label)) {
            _append(label);
            return KeyEventResult.handled;
          }
        }
        return KeyEventResult.ignored;
      },
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 圆点指示
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: List.generate(widget.length, (i) {
              final filled = i < _value.length;
              return Container(
                margin: const EdgeInsets.symmetric(horizontal: 8),
                width: 16,
                height: 16,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: filled ? scheme.primary : Colors.transparent,
                  border: Border.all(
                    color: widget.error != null ? scheme.error : scheme.outline,
                    width: 1.5,
                  ),
                ),
              );
            }),
          ),
          const SizedBox(height: 16),
          SizedBox(
            height: 24,
            child: widget.busy
                ? const Center(
                    child: SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  )
                : Text(
                    widget.error ?? '',
                    style: TextStyle(color: scheme.error),
                    textAlign: TextAlign.center,
                  ),
          ),
          const SizedBox(height: 12),
          _NumberGrid(onDigit: _append, onBackspace: _backspace),
        ],
      ),
    );
  }
}

class _NumberGrid extends StatelessWidget {
  const _NumberGrid({required this.onDigit, required this.onBackspace});
  final ValueChanged<String> onDigit;
  final VoidCallback onBackspace;

  @override
  Widget build(BuildContext context) {
    Widget key(String label, {VoidCallback? onTap, IconData? icon}) {
      return Padding(
        padding: const EdgeInsets.all(8),
        child: SizedBox(
          width: 72,
          height: 60,
          child: FilledButton.tonal(
            onPressed: onTap ?? (label.isEmpty ? null : () => onDigit(label)),
            style: FilledButton.styleFrom(
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
              ),
            ),
            child: icon != null
                ? Icon(icon)
                : Text(label, style: const TextStyle(fontSize: 22)),
          ),
        ),
      );
    }

    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 280),
      child: Wrap(
        alignment: WrapAlignment.center,
        children: [
          for (var i = 1; i <= 9; i++) key('$i'),
          key(''),
          key('0'),
          key('', onTap: onBackspace, icon: Icons.backspace_outlined),
        ],
      ),
    );
  }
}

