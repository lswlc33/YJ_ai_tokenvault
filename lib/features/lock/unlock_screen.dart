import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/theme_ext.dart';
import '../../state/lock_controller.dart';
import 'pin_pad.dart';

/// 解锁页：优先生物识别，降级 PIN。
class UnlockScreen extends ConsumerWidget {
  const UnlockScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(lockControllerProvider);
    final theme = Theme.of(context);
    final style = context.appStyle;

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
                Icon(Icons.shield_outlined,
                    size: 56, color: theme.colorScheme.primary),
                SizedBox(height: style.sectionSpacing),
                Text('解锁元记', style: theme.textTheme.titleLarge),
                if (state.biometricMode) ...[
                  SizedBox(height: style.itemSpacing),
                  Text('正在验证生物识别…',
                      style: theme.textTheme.bodyMedium
                          ?.copyWith(color: theme.hintColor)),
                ],
                SizedBox(height: style.largeSpacing),

                // 生物识别按钮
                if (state.biometricAvailable && !state.biometricMode) ...[
                  SizedBox(
                    width: double.infinity,
                    height: 56,
                    child: FilledButton.icon(
                      onPressed: state.busy
                          ? null
                          : () => ref
                              .read(lockControllerProvider.notifier)
                              .unlockWithBiometric(),
                      icon: const Icon(Icons.fingerprint, size: 28),
                      label: const Text('生物识别解锁'),
                    ),
                  ),
                  SizedBox(height: style.sectionSpacing),
                  Row(
                    children: [
                      const Expanded(child: Divider()),
                      Padding(
                        padding: EdgeInsets.symmetric(
                            horizontal: style.smallSpacing),
                        child: Text('或使用 PIN',
                            style: theme.textTheme.bodySmall
                                ?.copyWith(color: theme.hintColor)),
                      ),
                      const Expanded(child: Divider()),
                    ],
                  ),
                  SizedBox(height: style.sectionSpacing),
                ],

                // PIN 输入
                PinPad(
                  onCompleted: (pin) =>
                      ref.read(lockControllerProvider.notifier).unlock(pin),
                  error: state.error,
                  busy: state.busy,
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
