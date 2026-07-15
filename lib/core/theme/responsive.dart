import 'package:flutter/widgets.dart';

/// 布局形态：随窗口/屏幕宽度切换。桌面拖拽缩放时实时变化，
/// 方便预览手机 / 平板两套排布并发现错位。
enum FormFactor { phone, tablet, desktop }

class Breakpoints {
  static const double tablet = 600; // >=600 视为平板
  static const double desktop = 1000; // >=1000 视为桌面宽屏

  static FormFactor of(double width) {
    if (width >= desktop) return FormFactor.desktop;
    if (width >= tablet) return FormFactor.tablet;
    return FormFactor.phone;
  }
}

/// 响应式脚手架：根据可用宽度选择列数、是否用侧边导航等。
class Responsive {
  const Responsive(this.width);
  final double width;

  factory Responsive.of(BuildContext context) =>
      Responsive(MediaQuery.sizeOf(context).width);

  FormFactor get formFactor => Breakpoints.of(width);
  bool get isPhone => formFactor == FormFactor.phone;
  bool get isTablet => formFactor == FormFactor.tablet;
  bool get isDesktop => formFactor == FormFactor.desktop;

  /// 底部导航（手机）vs 侧边 NavigationRail（平板/桌面）。
  bool get useSideRail => !isPhone;

  /// 厂家卡片网格列数。
  int get gridColumns {
    switch (formFactor) {
      case FormFactor.desktop:
        return 3;
      case FormFactor.tablet:
        return 2;
      case FormFactor.phone:
        return 1;
    }
  }

  /// 内容最大宽度（超宽屏居中，避免卡片拉太长）。
  double get contentMaxWidth => isDesktop ? 1200 : double.infinity;
}
