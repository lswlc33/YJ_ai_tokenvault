import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// 迁移脚本集合（计划 §2.2）。onUpgrade 按 schema_version 顺序执行，
/// 只增列/建表，保留旧数据。
class Migrations {
  static const int latestVersion = 7;

  static Future<void> onCreate(Database db, int version) async {
    await _createV1(db);
    if (version >= 2) await _upgradeToV2(db);
    if (version >= 3) await _upgradeToV3(db);
    if (version >= 4) await _upgradeToV4(db);
    if (version >= 5) await _upgradeToV5(db);
    if (version >= 6) await _upgradeToV6(db);
    if (version >= 7) await _upgradeToV7(db);
  }

  static Future<void> onUpgrade(
    Database db,
    int oldVersion,
    int newVersion,
  ) async {
    if (oldVersion < 2) await _upgradeToV2(db);
    if (oldVersion < 3) await _upgradeToV3(db);
    if (oldVersion < 4) await _upgradeToV4(db);
    if (oldVersion < 5) await _upgradeToV5(db);
    if (oldVersion < 6) await _upgradeToV6(db);
    if (oldVersion < 7) await _upgradeToV7(db);
  }

  static Future<void> _createV1(Database db) async {
    await db.execute('''
      CREATE TABLE categories (
        id         INTEGER PRIMARY KEY,
        name       TEXT NOT NULL,
        sort_order INTEGER NOT NULL DEFAULT 0
      );
    ''');

    await db.insert('categories', {
      'id': 0,
      'name': '未分类',
      'sort_order': -1,
    });

    await db.execute('''
      CREATE TABLE providers (
        id             INTEGER PRIMARY KEY AUTOINCREMENT,
        name           TEXT NOT NULL,
        base_url_enc   TEXT,
        category_id    INTEGER NOT NULL DEFAULT 0,
        currency       TEXT NOT NULL DEFAULT 'USD',
        note           TEXT,
        color          INTEGER,
        probe_strategy TEXT,
        sort_order     INTEGER NOT NULL DEFAULT 0,
        created_at     INTEGER NOT NULL DEFAULT 0,
        updated_at     INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET DEFAULT
      );
    ''');
    await db.execute(
      'CREATE INDEX idx_providers_category ON providers(category_id);',
    );

    await db.execute('''
      CREATE TABLE api_keys (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        provider_id     INTEGER NOT NULL,
        label           TEXT NOT NULL DEFAULT '',
        api_key_enc     TEXT,
        balance         REAL,
        balance_text    TEXT,
        status          INTEGER NOT NULL DEFAULT 0,
        last_checked_at INTEGER,
        probe_override  TEXT,
        sort_order      INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE
      );
    ''');
    await db.execute(
      'CREATE INDEX idx_api_keys_provider ON api_keys(provider_id);',
    );

    await db.execute('''
      CREATE TABLE settings (
        key   TEXT PRIMARY KEY,
        value TEXT
      );
    ''');
  }

  static Future<void> _upgradeToV2(Database db) async {
    await db.execute('CREATE TABLE providers_new ('
        'id             INTEGER PRIMARY KEY AUTOINCREMENT,'
        'name           TEXT NOT NULL,'
        'base_url_enc   TEXT,'
        'category_id    INTEGER NOT NULL DEFAULT 0,'
        'note           TEXT,'
        'color          INTEGER,'
        'probe_strategy TEXT,'
        'sort_order     INTEGER NOT NULL DEFAULT 0,'
        'created_at     INTEGER NOT NULL DEFAULT 0,'
        'updated_at     INTEGER NOT NULL DEFAULT 0,'
        'FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET DEFAULT'
        ')');
    await db.execute('INSERT INTO providers_new '
        '(id, name, base_url_enc, category_id, note, color, probe_strategy, sort_order, created_at, updated_at) '
        'SELECT id, name, base_url_enc, category_id, note, color, probe_strategy, sort_order, created_at, updated_at '
        'FROM providers');
    await db.execute('DROP TABLE providers');
    await db.execute('ALTER TABLE providers_new RENAME TO providers');
    await db.execute(
      'CREATE INDEX idx_providers_category ON providers(category_id);',
    );
  }

  static Future<void> _upgradeToV3(Database db) async {
    await db.execute(
      'ALTER TABLE api_keys ADD COLUMN balance_query_enabled INTEGER NOT NULL DEFAULT 0',
    );
  }

  static Future<void> _upgradeToV4(Database db) async {
    await db.execute(
      'ALTER TABLE api_keys ADD COLUMN probe_method TEXT',
    );
    await db.execute(
      'ALTER TABLE api_keys ADD COLUMN custom_probe_url TEXT',
    );
    await db.execute(
      'ALTER TABLE api_keys ADD COLUMN models TEXT',
    );
  }

  /// V5: 重写探测系统 schema。
  /// - providers: 删除 probe_strategy，添加 probe_type
  /// - api_keys: 添加 per-key 探测开关 + 余额端点配置，删除旧探测字段
  static Future<void> _upgradeToV5(Database db) async {
    // --- providers 表重建 ---
    await db.execute('CREATE TABLE providers_new ('
        'id             INTEGER PRIMARY KEY AUTOINCREMENT,'
        'name           TEXT NOT NULL,'
        'base_url_enc   TEXT,'
        'category_id    INTEGER NOT NULL DEFAULT 0,'
        'note           TEXT,'
        'color          INTEGER,'
        'probe_type     TEXT NOT NULL DEFAULT \'builtin\','
        'sort_order     INTEGER NOT NULL DEFAULT 0,'
        'created_at     INTEGER NOT NULL DEFAULT 0,'
        'updated_at     INTEGER NOT NULL DEFAULT 0,'
        'FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET DEFAULT'
        ')');
    await db.execute('INSERT INTO providers_new '
        '(id, name, base_url_enc, category_id, note, color, probe_type, sort_order, created_at, updated_at) '
        'SELECT id, name, base_url_enc, category_id, note, color, '
        'CASE '
        '  WHEN probe_strategy IS NOT NULL AND probe_strategy != \'\' THEN '
        '    CASE WHEN probe_strategy = \'openai_compatible\' THEN \'custom\' ELSE \'builtin\' END '
        '  ELSE \'builtin\' END, '
        'sort_order, created_at, updated_at '
        'FROM providers');
    await db.execute('DROP TABLE providers');
    await db.execute('ALTER TABLE providers_new RENAME TO providers');
    await db.execute(
      'CREATE INDEX idx_providers_category ON providers(category_id);',
    );

    // --- api_keys 表重建 ---
    await db.execute('CREATE TABLE api_keys_new ('
        'id                    INTEGER PRIMARY KEY AUTOINCREMENT,'
        'provider_id           INTEGER NOT NULL,'
        'label                 TEXT NOT NULL DEFAULT \'\','
        'api_key_enc           TEXT,'
        'status                INTEGER NOT NULL DEFAULT 0,'
        'last_checked_at       INTEGER,'
        'sort_order            INTEGER NOT NULL DEFAULT 0,'
        'models                TEXT,'
        'key_check_enabled     INTEGER NOT NULL DEFAULT 1,'
        'model_list_enabled    INTEGER NOT NULL DEFAULT 1,'
        'balance_check_enabled INTEGER NOT NULL DEFAULT 0,'
        'balance_endpoint      TEXT NOT NULL DEFAULT \'/user/balance\','
        'balance_value_path    TEXT NOT NULL DEFAULT \'total_credits\','
        'balance_usage_path    TEXT NOT NULL DEFAULT \'total_usage\','
        'models_endpoint       TEXT NOT NULL DEFAULT \'/models\','
        'balance               REAL,'
        'balance_text          TEXT,'
        'FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE'
        ')');
    await db.execute('INSERT INTO api_keys_new '
        '(id, provider_id, label, api_key_enc, status, last_checked_at, sort_order, models, '
        'key_check_enabled, model_list_enabled, balance_check_enabled, '
        'balance_endpoint, balance_value_path, balance_usage_path, models_endpoint, '
        'balance, balance_text) '
        'SELECT id, provider_id, label, api_key_enc, status, last_checked_at, sort_order, models, '
        '1, 1, '
        'CASE WHEN balance_query_enabled = 1 THEN 1 ELSE 0 END, '
        '\'/user/balance\', \'total_credits\', \'total_usage\', \'/models\', '
        'balance, balance_text '
        'FROM api_keys');
    await db.execute('DROP TABLE api_keys');
    await db.execute('ALTER TABLE api_keys_new RENAME TO api_keys');
    await db.execute(
      'CREATE INDEX idx_api_keys_provider ON api_keys(provider_id);',
    );
  }

  /// V6: 添加 providers.api_path 列。
  static Future<void> _upgradeToV6(Database db) async {
    await db.execute(
      "ALTER TABLE providers ADD COLUMN api_path TEXT NOT NULL DEFAULT ''",
    );
  }

  /// V7: 修正探测 URL 拼接。
  /// - api_keys: models_endpoint / balance_endpoint 去掉 /v1 前缀（由 api_path 提供）
  /// - providers: api_path 从完整路径改为仅前缀（v1/models → v1）
  static Future<void> _upgradeToV7(Database db) async {
    // Strip /v1 prefix from models_endpoint
    await db.rawUpdate(
      "UPDATE api_keys SET models_endpoint = SUBSTR(models_endpoint, 4) "
      "WHERE models_endpoint LIKE '/v1/%'",
    );
    // Strip /v1 prefix from balance_endpoint
    await db.rawUpdate(
      "UPDATE api_keys SET balance_endpoint = SUBSTR(balance_endpoint, 4) "
      "WHERE balance_endpoint LIKE '/v1/%'",
    );
    // Fix api_path: strip /models suffix to keep only the version prefix
    await db.rawUpdate(
      "UPDATE providers SET api_path = RTRIM("
      "SUBSTR(api_path, 1, LENGTH(api_path) - LENGTH('/models')), '/') "
      "WHERE api_path LIKE '%/models'",
    );
  }
}
