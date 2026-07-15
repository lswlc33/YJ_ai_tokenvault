import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';

import '../../models/api_key.dart';
import '../../models/key_status.dart';
import '../../models/model_info.dart';
import '../log/log_service.dart';

class ProbeResult {
  const ProbeResult({
    required this.status,
    this.balance,
    this.balanceText,
    this.models = const [],
  });

  final KeyStatus status;
  final double? balance;
  final String? balanceText;
  final List<ModelInfo> models;
}

class ProbeService {
  ProbeService({Dio? dio, String? proxy}) : _dio = dio ?? Dio() {
    _dio.interceptors.add(_ProbeInterceptor());
    _dio.options.connectTimeout = const Duration(seconds: 15);
    _dio.options.receiveTimeout = const Duration(seconds: 15);
    _configureProxy(proxy);
  }

  final Dio _dio;
  static const _src = 'HTTP';

  void _configureProxy(String? proxy) {
    final adapter = _dio.httpClientAdapter;
    if (adapter is IOHttpClientAdapter) {
      adapter.createHttpClient = () {
        final client = HttpClient();
        // 优先使用用户配置的代理
        if (proxy != null && proxy.isNotEmpty) {
          client.findProxy = (uri) => 'PROXY $proxy';
          log.info('使用代理: $proxy', source: _src);
        } else {
          // 尝试读取系统代理环境变量
          final envProxy = Platform.environment['https_proxy'] ??
              Platform.environment['HTTPS_PROXY'] ??
              Platform.environment['http_proxy'] ??
              Platform.environment['HTTP_PROXY'];
          if (envProxy != null && envProxy.isNotEmpty) {
            final proxyUri = Uri.tryParse(envProxy);
            if (proxyUri != null && proxyUri.host.isNotEmpty) {
              final proxyHost = proxyUri.host;
              final proxyPort = proxyUri.hasPort ? proxyUri.port : 8080;
              client.findProxy =
                  (uri) => 'PROXY $proxyHost:$proxyPort';
              log.info('使用环境变量代理: $envProxy', source: _src);
            }
          }
        }
        return client;
      };
    }
  }

  Future<ProbeResult> probeAllChecks(
    ApiKey key, {
    required String baseUrl,
  }) async {
    final apiKey = key.apiKey;
    if (apiKey == null || apiKey.isEmpty) {
      log.warn('Key 为空，跳过探测', source: _src);
      return const ProbeResult(status: KeyStatus.invalid);
    }
    final base = _normalizeBase(baseUrl);
    final label = key.label.isNotEmpty ? key.label : 'Key#${key.id}';

    bool? keyValid;
    List<ModelInfo> models = [];
    double? balance;
    String? balanceText;

    if (key.keyCheckEnabled || key.modelListEnabled) {
      final modelsUrl = '$base${key.modelsEndpoint}';
      log.info('▶ 探测可用性/模型列表 [$label]', source: _src);
      try {
        final resp = await _dio.get(
          modelsUrl,
          options: Options(
            headers: {'Authorization': 'Bearer $apiKey'},
          ),
        );
        if (resp.statusCode == 200 && resp.data is Map) {
          keyValid = true;
          if (key.modelListEnabled) {
            models = _extractModels(resp.data.cast<String, dynamic>());
            log.info('✓ 解析到 ${models.length} 个模型', source: _src);
          }
        } else {
          keyValid = false;
        }
      } on DioException catch (e) {
        keyValid = false;
        _logNetworkError(e, modelsUrl, label);
      } catch (e) {
        keyValid = false;
        log.error('未知错误: $e', source: _src);
      }
    }

    if (key.balanceCheckEnabled) {
      final balanceUrl = '$base${key.balanceEndpoint}';
      log.info('▶ 探测余额 [$label]', source: _src);
      try {
        final resp = await _dio.get(
          balanceUrl,
          options: Options(
            headers: {'Authorization': 'Bearer $apiKey'},
          ),
        );
        if (resp.statusCode == 200 && resp.data is Map) {
          final data = resp.data.cast<String, dynamic>();
          final value = _resolveJsonPath(data, key.balanceValuePath);
          final usage = _resolveJsonPath(data, key.balanceUsagePath);
          if (value != null && usage != null) {
            balance = _roundBalance(value - usage);
            balanceText = _formatBalance(balance);
            log.info('✓ 余额: $value - $usage = $balance', source: _src);
          } else if (value != null) {
            balance = _roundBalance(value);
            balanceText = _formatBalance(balance);
            log.info('✓ 余额: $balance', source: _src);
          } else {
            log.warn(
                '余额路径解析失败: valuePath=${key.balanceValuePath}',
                source: _src);
          }
        }
      } on DioException catch (e) {
        _logNetworkError(e, balanceUrl, label);
      } catch (e) {
        log.error('未知错误: $e', source: _src);
      }
    }

    KeyStatus status;
    if (key.keyCheckEnabled) {
      status = keyValid == true ? KeyStatus.ok : KeyStatus.invalid;
      if (status == KeyStatus.ok && key.balanceCheckEnabled) {
        if (balance != null) {
          status = _balanceStatus(balance);
        }
      }
    } else if (key.balanceCheckEnabled) {
      status = balance != null ? _balanceStatus(balance) : KeyStatus.unknown;
    } else {
      status = KeyStatus.unknown;
    }

    log.info('探测完成 [$label]: ${status.name}', source: _src);

    return ProbeResult(
      status: status,
      balance: balance,
      balanceText: balanceText,
      models: models,
    );
  }

  void _logNetworkError(DioException e, String url, String label) {
    final buf = StringBuffer()
      ..writeln('✗ 网络错误 [$label]')
      ..writeln('URL: $url')
      ..writeln('类型: ${e.type.name}')
      ..writeln('消息: ${e.message}');
    if (e.error is SocketException) {
      final se = e.error as SocketException;
      buf.writeln('Socket: ${se.message}');
      buf.writeln('地址: ${se.address}');
      buf.writeln('端口: ${se.port}');
      buf.writeln('');
      buf.writeln('排查建议:');
      buf.writeln('  1. 检查网络连接是否正常');
      buf.writeln('  2. 检查是否需要代理（系统设置→网络→代理）');
      buf.writeln('  3. 尝试 ping 目标域名');
      buf.writeln('  4. 检查防火墙/安全软件是否拦截');
    }
    log.error(buf.toString(), source: _src);
  }

  Future<List<ModelInfo>> fetchModels(ApiKey key, {String? baseUrl}) async {
    final apiKey = key.apiKey;
    if (apiKey == null || apiKey.isEmpty) return const [];
    final base = _normalizeBase(baseUrl);
    try {
      final resp = await _dio.get(
        '$base${key.modelsEndpoint}',
        options: Options(
          headers: {'Authorization': 'Bearer $apiKey'},
        ),
      );
      if (resp.statusCode == 200 && resp.data is Map) {
        return _extractModels(resp.data.cast<String, dynamic>());
      }
    } catch (_) {}
    return const [];
  }

  String _normalizeBase(String? url) {
    if (url == null || url.isEmpty) return '';
    return url.endsWith('/') ? url.substring(0, url.length - 1) : url;
  }

  List<ModelInfo> _extractModels(Map<String, dynamic> data) {
    final models = <ModelInfo>[];
    final data_ = data['data'];
    if (data_ is List) {
      for (final item in data_) {
        if (item is Map) {
          final id = item['id'];
          if (id is String) {
            final raw = Map<String, dynamic>.from(item)..remove('id');
            models.add(ModelInfo(id: id, raw: raw));
          }
        }
      }
    }
    models.sort((a, b) => a.id.compareTo(b.id));
    return models;
  }

  double? _resolveJsonPath(Map<String, dynamic> data, String path) {
    if (path.isEmpty) return null;
    final parts = path.split('.');
    dynamic current = data;
    for (final part in parts) {
      // 支持数组索引：balance_infos[0]
      final bracketIdx = part.indexOf('[');
      if (bracketIdx != -1 && part.endsWith(']')) {
        final key = part.substring(0, bracketIdx);
        final index =
            int.tryParse(part.substring(bracketIdx + 1, part.length - 1));
        if (current is Map && index != null) {
          current = current[key];
        } else {
          return null;
        }
        if (current is List && index < current.length) {
          current = current[index];
        } else {
          return null;
        }
      } else if (current is Map) {
        current = current[part];
      } else {
        return null;
      }
    }
    if (current is num) return current.toDouble();
    if (current is String) return double.tryParse(current);
    return null;
  }

  String? _formatBalance(double? balance) {
    if (balance == null) return null;
    return '\$${balance.toStringAsFixed(2)}';
  }

  KeyStatus _balanceStatus(double balance) {
    if (balance < 0) return KeyStatus.overdue;
    if (balance > 0 && balance < 5) return KeyStatus.insufficient;
    return KeyStatus.ok;
  }

  double _roundBalance(double v) => (v * 100).roundToDouble() / 100;
}

class _ProbeInterceptor extends Interceptor {
  static const _src = 'HTTP';

  String _prettyJson(dynamic data) {
    if (data == null) return '<empty>';
    if (data is String) {
      try {
        return const JsonEncoder.withIndent('  ').convert(jsonDecode(data));
      } catch (_) {
        return data;
      }
    }
    try {
      return const JsonEncoder.withIndent('  ').convert(data);
    } catch (_) {
      return data.toString();
    }
  }

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final buf = StringBuffer()
      ..writeln('┌─── HTTP REQUEST ───')
      ..writeln('METHOD: ${options.method}')
      ..writeln('URL: ${options.uri}')
      ..writeln('HEADERS:');
    options.headers.forEach((k, v) {
      if (k.toLowerCase() == 'authorization') {
        final s = v.toString();
        final show = s.length > 12 ? s.substring(0, 12) : 'Bearer ';
        buf.writeln('  $k: $show***');
      } else {
        buf.writeln('  $k: $v');
      }
    });
    if (options.data != null) {
      buf.writeln('BODY:');
      buf.writeln(_prettyJson(options.data));
    }
    buf.writeln('└────────────────────');
    log.info(buf.toString(), source: _src);
    handler.next(options);
  }

  @override
  void onResponse(Response response, ResponseInterceptorHandler handler) {
    final buf = StringBuffer()
      ..writeln('┌─── HTTP RESPONSE ───')
      ..writeln('URL: ${response.requestOptions.uri}')
      ..writeln('STATUS: ${response.statusCode}')
      ..writeln('HEADERS:');
    response.headers.forEach((k, v) {
      buf.writeln('  $k: $v');
    });
    buf.writeln('BODY:');
    buf.writeln(_prettyJson(response.data));
    buf.writeln('└─────────────────────');
    log.info(buf.toString(), source: _src);
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    final buf = StringBuffer()
      ..writeln('┌─── HTTP ERROR ───')
      ..writeln('URL: ${err.requestOptions.uri}')
      ..writeln('TYPE: ${err.type.name}')
      ..writeln('MESSAGE: ${err.message}');
    if (err.response != null) {
      buf.writeln('STATUS: ${err.response?.statusCode}');
      buf.writeln('RESPONSE BODY:');
      buf.writeln(_prettyJson(err.response?.data));
    }
    if (err.error is SocketException) {
      final se = err.error as SocketException;
      buf.writeln('SOCKET: ${se.message}');
    }
    buf.writeln('└───────────────────');
    log.error(buf.toString(), source: _src);
    handler.next(err);
  }
}
