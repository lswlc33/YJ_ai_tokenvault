import 'package:flutter/material.dart';

/// 风格化参数扩展：一套组件读它拿到圆角/投影/间距，从而适配双主题。
@immutable
class AppStyle extends ThemeExtension<AppStyle> {
  const AppStyle({
    required this.cardRadius,
    required this.cardElevation,
    required this.cardShadow,
    required this.pagePadding,
    required this.itemSpacing,
    required this.sectionSpacing,
    required this.chipRadius,
    required this.showDividers,
    required this.dense,
    required this.microSpacing,
    required this.smallSpacing,
    required this.cardPadding,
    required this.largeSpacing,
    required this.spinnerSize,
    required this.sectionHeaderColor,
  });

  final double cardRadius;
  final double cardElevation;
  final List<BoxShadow> cardShadow;
  final double pagePadding;
  final double itemSpacing;
  final double sectionSpacing;
  final double chipRadius;
  final bool showDividers; // MIUIx 风格明显分割线
  final bool dense;

  /// 4px — 内联元素间距（图标→文字、label→value）
  final double microSpacing;

  /// 8px — 小间距（chip 间距、分割线边距）
  final double smallSpacing;

  /// 卡片内部内边距（替代 itemSpacing + 2 的临时写法）
  final double cardPadding;

  /// 24px — 大段落间距（主要区块之间）
  final double largeSpacing;

  /// 加载指示器统一尺寸
  final double spinnerSize;

  /// 分组标题颜色
  final Color sectionHeaderColor;

  /// Material You：大圆角、M3 tonal elevation、无手绘阴影。
  static const material = AppStyle(
    cardRadius: 20,
    cardElevation: 0,
    cardShadow: [],
    pagePadding: 16,
    itemSpacing: 12,
    sectionSpacing: 16,
    chipRadius: 10,
    showDividers: false,
    dense: false,
    microSpacing: 4,
    smallSpacing: 8,
    cardPadding: 14,
    largeSpacing: 24,
    spinnerSize: 20,
    sectionHeaderColor: Color(0xFFBBC4EF),
  );

  /// MIUIx：圆角矩形、明显投影 + 分割线。
  static const miuix = AppStyle(
    cardRadius: 14,
    cardElevation: 0,
    cardShadow: [
      BoxShadow(
        color: Color(0x14000000),
        blurRadius: 10,
        offset: Offset(0, 2),
      ),
    ],
    pagePadding: 12,
    itemSpacing: 10,
    sectionSpacing: 12,
    chipRadius: 8,
    showDividers: true,
    dense: true,
    microSpacing: 4,
    smallSpacing: 6,
    cardPadding: 12,
    largeSpacing: 20,
    spinnerSize: 20,
    sectionHeaderColor: Color(0xFF999999),
  );

  @override
  AppStyle copyWith({
    double? cardRadius,
    double? cardElevation,
    List<BoxShadow>? cardShadow,
    double? pagePadding,
    double? itemSpacing,
    double? sectionSpacing,
    double? chipRadius,
    bool? showDividers,
    bool? dense,
    double? microSpacing,
    double? smallSpacing,
    double? cardPadding,
    double? largeSpacing,
    double? spinnerSize,
    Color? sectionHeaderColor,
  }) =>
      AppStyle(
        cardRadius: cardRadius ?? this.cardRadius,
        cardElevation: cardElevation ?? this.cardElevation,
        cardShadow: cardShadow ?? this.cardShadow,
        pagePadding: pagePadding ?? this.pagePadding,
        itemSpacing: itemSpacing ?? this.itemSpacing,
        sectionSpacing: sectionSpacing ?? this.sectionSpacing,
        chipRadius: chipRadius ?? this.chipRadius,
        showDividers: showDividers ?? this.showDividers,
        dense: dense ?? this.dense,
        microSpacing: microSpacing ?? this.microSpacing,
        smallSpacing: smallSpacing ?? this.smallSpacing,
        cardPadding: cardPadding ?? this.cardPadding,
        largeSpacing: largeSpacing ?? this.largeSpacing,
        spinnerSize: spinnerSize ?? this.spinnerSize,
        sectionHeaderColor: sectionHeaderColor ?? this.sectionHeaderColor,
      );

  @override
  AppStyle lerp(ThemeExtension<AppStyle>? other, double t) {
    if (other is! AppStyle) return this;
    return AppStyle(
      cardRadius: _lerp(cardRadius, other.cardRadius, t),
      cardElevation: _lerp(cardElevation, other.cardElevation, t),
      cardShadow: t < 0.5 ? cardShadow : other.cardShadow,
      pagePadding: _lerp(pagePadding, other.pagePadding, t),
      itemSpacing: _lerp(itemSpacing, other.itemSpacing, t),
      sectionSpacing: _lerp(sectionSpacing, other.sectionSpacing, t),
      chipRadius: _lerp(chipRadius, other.chipRadius, t),
      showDividers: t < 0.5 ? showDividers : other.showDividers,
      dense: t < 0.5 ? dense : other.dense,
      microSpacing: _lerp(microSpacing, other.microSpacing, t),
      smallSpacing: _lerp(smallSpacing, other.smallSpacing, t),
      cardPadding: _lerp(cardPadding, other.cardPadding, t),
      largeSpacing: _lerp(largeSpacing, other.largeSpacing, t),
      spinnerSize: _lerp(spinnerSize, other.spinnerSize, t),
      sectionHeaderColor: Color.lerp(sectionHeaderColor, other.sectionHeaderColor, t)!,
    );
  }

  static double _lerp(double a, double b, double t) => a + (b - a) * t;
}

/// 便捷读取：`context.appStyle`。
extension AppStyleContext on BuildContext {
  AppStyle get appStyle =>
      Theme.of(this).extension<AppStyle>() ?? AppStyle.material;
}
