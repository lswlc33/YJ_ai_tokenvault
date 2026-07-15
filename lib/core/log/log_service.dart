import 'dart:async';
import 'dart:collection';

/// 日志级别。
enum LogLevel {
  debug(0, 'DEBUG'),
  info(1, 'INFO'),
  warn(2, 'WARN'),
  error(3, 'ERROR');

  const LogLevel(this.value, this.label);
  final int value;
  final String label;
}

/// 单条日志。
class LogEntry {
  const LogEntry({
    required this.time,
    required this.level,
    required this.message,
    this.detail,
    this.source,
  });

  final DateTime time;
  final LogLevel level;
  final String message;
  final String? detail;
  final String? source;

  String get timeStr =>
      '${time.hour.toString().padLeft(2, '0')}:'
      '${time.minute.toString().padLeft(2, '0')}:'
      '${time.second.toString().padLeft(2, '0')}';
}

/// 全局日志服务（内存环形缓冲，最多 500 条）。
class LogService {
  LogService._();
  static final LogService instance = LogService._();

  static const int _maxEntries = 500;
  final Queue<LogEntry> _entries = Queue();
  final _controller = StreamController<List<LogEntry>>.broadcast();

  /// 日志流（UI 监听刷新）。
  Stream<List<LogEntry>> get stream => _controller.stream;

  /// 当前快照。
  List<LogEntry> get entries => UnmodifiableListView(_entries);

  void debug(String msg, {String? detail, String? source}) =>
      _add(LogLevel.debug, msg, detail: detail, source: source);

  void info(String msg, {String? detail, String? source}) =>
      _add(LogLevel.info, msg, detail: detail, source: source);

  void warn(String msg, {String? detail, String? source}) =>
      _add(LogLevel.warn, msg, detail: detail, source: source);

  void error(String msg, {String? detail, String? source}) =>
      _add(LogLevel.error, msg, detail: detail, source: source);

  void _add(LogLevel level, String msg, {String? detail, String? source}) {
    final entry = LogEntry(
      time: DateTime.now(),
      level: level,
      message: msg,
      detail: detail,
      source: source,
    );
    if (_entries.length >= _maxEntries) {
      _entries.removeFirst();
    }
    _entries.addLast(entry);
    _controller.add(List.unmodifiable(_entries));
  }

  void clear() {
    _entries.clear();
    _controller.add(const []);
  }

  void dispose() {
    _controller.close();
  }
}

/// 全局日志实例。
final log = LogService.instance;
