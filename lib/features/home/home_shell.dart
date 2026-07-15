import 'package:flutter/material.dart';

import '../../core/theme/responsive.dart';
import '../dashboard/dashboard_screen.dart';
import '../log/log_screen.dart';
import '../settings/settings_screen.dart';

/// 主壳：根据窗口宽度在底部导航（手机）与侧边 NavigationRail（平板/桌面）间切换。
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  static const _destinations = [
    (icon: Icons.dashboard_outlined, selected: Icons.dashboard, label: '仪表盘'),
    (icon: Icons.bug_report_outlined, selected: Icons.bug_report, label: '日志'),
    (icon: Icons.settings_outlined, selected: Icons.settings, label: '设置'),
  ];

  static final _pages = <Widget>[
    const DashboardScreen(),
    const LogScreen(),
    const SettingsScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    final r = Responsive.of(context);

    if (r.useSideRail) {
      return PopScope(
        canPop: _index == 0,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop && _index != 0) setState(() => _index = 0);
        },
        child: Scaffold(
          body: Row(
            children: [
              NavigationRail(
                selectedIndex: _index,
                onDestinationSelected: (i) => setState(() => _index = i),
                labelType: r.isDesktop
                    ? NavigationRailLabelType.all
                    : NavigationRailLabelType.selected,
                extended: false,
                destinations: [
                  for (final d in _destinations)
                    NavigationRailDestination(
                      icon: Icon(d.icon),
                      selectedIcon: Icon(d.selected),
                      label: Text(d.label),
                    ),
                ],
              ),
              const VerticalDivider(width: 1),
              Expanded(
                child: IndexedStack(index: _index, children: _pages),
              ),
            ],
          ),
        ),
      );
    }

    return PopScope(
      canPop: _index == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && _index != 0) setState(() => _index = 0);
      },
      child: Scaffold(
        body: IndexedStack(index: _index, children: _pages),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _index,
          onDestinationSelected: (i) => setState(() => _index = i),
          destinations: [
            for (final d in _destinations)
              NavigationDestination(
                icon: Icon(d.icon),
                selectedIcon: Icon(d.selected),
                label: d.label,
              ),
          ],
        ),
      ),
    );
  }
}
