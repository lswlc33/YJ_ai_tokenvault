enum KeyStatus {
  unknown(0),
  ok(1),
  invalid(2),
  insufficient(3),
  overdue(5);

  const KeyStatus(this.value);
  final int value;

  static KeyStatus fromValue(int? v) {
    switch (v) {
      case 1:
        return KeyStatus.ok;
      case 2:
        return KeyStatus.invalid;
      case 3:
        return KeyStatus.insufficient;
      case 5:
        return KeyStatus.overdue;
      default:
        return KeyStatus.unknown;
    }
  }
}
