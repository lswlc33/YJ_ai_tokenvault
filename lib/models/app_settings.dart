/// 设置项 KV 键名常量（计划 §2.1 settings 表）。
class SettingsKeys {
  static const kdfSalt = 'kdf_salt';
  static const kdfIterations = 'kdf_iterations';
  static const pinVerifier = 'pin_verifier';

  static const webdavUrl = 'webdav_url';
  static const webdavUserEnc = 'webdav_user_enc';
  static const webdavPassEnc = 'webdav_pass_enc';
  static const webdavDir = 'webdav_dir';

  static const themeMode = 'theme_mode'; // material / miuix
  static const darkMode = 'dark_mode'; // system / light / dark

  static const backupIntervalDays = 'backup_interval_days'; // 默认 7
  static const lastArchiveAt = 'last_archive_at';
  static const autoLockSeconds = 'auto_lock_seconds'; // 默认 60，0=关
  static const balanceQueryEnabled = 'balance_query_enabled'; // 余额查询开关

  static const autoProbeOnUnlock = 'auto_probe_on_unlock'; // 解锁时自动探测
  static const autoProbeIntervalMinutes =
      'auto_probe_interval_minutes'; // 定时探测间隔（分钟），0=关

  static const modelMetadataUrl =
      'model_metadata_url'; // 模型元数据接口地址
  static const lastMetadataFetchAt =
      'last_metadata_fetch_at'; // 上次元数据拉取时间

  static const deviceId = 'device_id';
  static const dataRevision = 'data_revision';
}

enum AppThemeStyle { material, miuix }

enum AppDarkMode { system, light, dark }

/// 强类型设置快照。从 KV map 读，写回 KV。
class AppSettings {
  const AppSettings({
    this.themeStyle = AppThemeStyle.material,
    this.darkMode = AppDarkMode.system,
    this.backupIntervalDays = 7,
    this.autoLockSeconds = 60,
    this.balanceQueryEnabled = false,
    this.autoProbeOnUnlock = false,
    this.autoProbeIntervalMinutes = 0,
    this.modelMetadataUrl = 'https://models.dev/models.json',
    this.lastMetadataFetchAt,
    this.webdavUrl,
    this.webdavDir = '/AiTokenVault',
    this.lastArchiveAt,
    this.dataRevision = 0,
  });

  final AppThemeStyle themeStyle;
  final AppDarkMode darkMode;
  final int backupIntervalDays;
  final int autoLockSeconds;
  final bool balanceQueryEnabled;
  final bool autoProbeOnUnlock;
  final int autoProbeIntervalMinutes;
  final String modelMetadataUrl;
  final int? lastMetadataFetchAt;
  final String? webdavUrl;
  final String webdavDir;
  final int? lastArchiveAt;
  final int dataRevision;

  bool get autoLockEnabled => autoLockSeconds > 0;

  AppSettings copyWith({
    AppThemeStyle? themeStyle,
    AppDarkMode? darkMode,
    int? backupIntervalDays,
    int? autoLockSeconds,
    bool? balanceQueryEnabled,
    bool? autoProbeOnUnlock,
    int? autoProbeIntervalMinutes,
    String? modelMetadataUrl,
    Object? lastMetadataFetchAt = _sentinel,
    Object? webdavUrl = _sentinel,
    String? webdavDir,
    Object? lastArchiveAt = _sentinel,
    int? dataRevision,
  }) =>
      AppSettings(
        themeStyle: themeStyle ?? this.themeStyle,
        darkMode: darkMode ?? this.darkMode,
        backupIntervalDays: backupIntervalDays ?? this.backupIntervalDays,
        autoLockSeconds: autoLockSeconds ?? this.autoLockSeconds,
        balanceQueryEnabled: balanceQueryEnabled ?? this.balanceQueryEnabled,
        autoProbeOnUnlock: autoProbeOnUnlock ?? this.autoProbeOnUnlock,
        autoProbeIntervalMinutes:
            autoProbeIntervalMinutes ?? this.autoProbeIntervalMinutes,
        modelMetadataUrl: modelMetadataUrl ?? this.modelMetadataUrl,
        lastMetadataFetchAt: lastMetadataFetchAt == _sentinel
            ? this.lastMetadataFetchAt
            : lastMetadataFetchAt as int?,
        webdavUrl: webdavUrl == _sentinel ? this.webdavUrl : webdavUrl as String?,
        webdavDir: webdavDir ?? this.webdavDir,
        lastArchiveAt: lastArchiveAt == _sentinel
            ? this.lastArchiveAt
            : lastArchiveAt as int?,
        dataRevision: dataRevision ?? this.dataRevision,
      );

  factory AppSettings.fromKv(Map<String, String> kv) {
    AppThemeStyle style() =>
        kv[SettingsKeys.themeMode] == 'miuix'
            ? AppThemeStyle.miuix
            : AppThemeStyle.material;
    AppDarkMode dark() {
      switch (kv[SettingsKeys.darkMode]) {
        case 'light':
          return AppDarkMode.light;
        case 'dark':
          return AppDarkMode.dark;
        default:
          return AppDarkMode.system;
      }
    }

    return AppSettings(
      themeStyle: style(),
      darkMode: dark(),
      backupIntervalDays:
          int.tryParse(kv[SettingsKeys.backupIntervalDays] ?? '') ?? 7,
      autoLockSeconds:
          int.tryParse(kv[SettingsKeys.autoLockSeconds] ?? '') ?? 60,
      balanceQueryEnabled:
          kv[SettingsKeys.balanceQueryEnabled] == 'true',
      autoProbeOnUnlock:
          kv[SettingsKeys.autoProbeOnUnlock] == 'true',
      autoProbeIntervalMinutes:
          int.tryParse(kv[SettingsKeys.autoProbeIntervalMinutes] ?? '') ?? 0,
      modelMetadataUrl:
          kv[SettingsKeys.modelMetadataUrl] ?? 'https://models.dev/models.json',
      lastMetadataFetchAt:
          int.tryParse(kv[SettingsKeys.lastMetadataFetchAt] ?? ''),
      webdavUrl: kv[SettingsKeys.webdavUrl],
      webdavDir: kv[SettingsKeys.webdavDir] ?? '/AiTokenVault',
      lastArchiveAt: int.tryParse(kv[SettingsKeys.lastArchiveAt] ?? ''),
      dataRevision: int.tryParse(kv[SettingsKeys.dataRevision] ?? '') ?? 0,
    );
  }

  static const Object _sentinel = Object();
}
