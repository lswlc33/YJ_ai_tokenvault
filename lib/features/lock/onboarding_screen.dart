import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/theme_ext.dart';
import '../../state/lock_controller.dart';
import 'pin_pad.dart';

/// 首启引导：设置 6 位 PIN（两次确认）。
class OnboardingScreen extends ConsumerStatefulWidget {
  const OnboardingScreen({super.key});

  @override
  ConsumerState<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends ConsumerState<OnboardingScreen> {
  String? _first;
  String? _localError;

  void _handle(String pin) {
    if (_first == null) {
      setState(() {
        _first = pin;
        _localError = null;
      });
      return;
    }
    if (_first != pin) {
      setState(() {
        _first = null;
        _localError = '两次输入不一致，请重新设置';
      });
      return;
    }
    ref.read(lockControllerProvider.notifier).setupPin(pin);
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(lockControllerProvider);
    final theme = Theme.of(context);
    final style = context.appStyle;
    final title = _first == null ? '设置 6 位 PIN' : '再次输入以确认';

    return PopScope(
      canPop: false,
      child: Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Padding(
            padding: EdgeInsets.all(style.largeSpacing),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.lock_outline,
                    size: 56, color: theme.colorScheme.primary),
                SizedBox(height: style.sectionSpacing),
                Text('元记',
                    style: theme.textTheme.headlineSmall),
                SizedBox(height: style.smallSpacing),
                Text(
                  '词元记录 · 纯本地加密存储',
                  style: theme.textTheme.bodySmall,
                  textAlign: TextAlign.center,
                ),
                SizedBox(height: style.largeSpacing),
                Text(title, style: theme.textTheme.titleMedium),
                SizedBox(height: style.largeSpacing - style.smallSpacing),
                PinPad(
                  onCompleted: _handle,
                  error: _localError ?? state.error,
                  busy: state.busy,
                ),
                SizedBox(height: style.sectionSpacing),
                Text(
                  '多设备以最后一次保存的设备为准；PIN 无法找回，请牢记。',
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.hintColor),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
      ),
      ),
    );
  }
}
