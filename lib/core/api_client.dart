import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:pretty_dio_logger/pretty_dio_logger.dart';

import '../config/app_config.dart';

class ApiResult<T> {
  final T? data;
  final String? error;
  final bool success;

  ApiResult.success(this.data) : success = true, error = null;
  ApiResult.failure(this.error) : success = false, data = null;
}

class ApiClient {
  static final ApiClient _instance = ApiClient._internal();
  factory ApiClient() => _instance;
  ApiClient._internal();

  late final Dio _dio;

  // Token provider abstraction (future-ready for secure storage)
  String? _bearerToken;
  
  void setBearerToken(String token) {
    _bearerToken = token;
  }

  void initialize() {
    _dio = Dio(BaseOptions(
      baseUrl: AppConfig.apiBaseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
      sendTimeout: const Duration(seconds: 10),
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    ));

    // Add authorization interceptor
    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          if (_bearerToken != null) {
            options.headers['Authorization'] = 'Bearer $_bearerToken';
          }
          handler.next(options);
        },
      ),
    );

    // Add logging interceptor only in debug mode
    if (kDebugMode) {
      _dio.interceptors.add(
        PrettyDioLogger(
          requestHeader: true,
          requestBody: true,
          responseBody: true,
          responseHeader: false,
          error: true,
          compact: true,
          maxWidth: 90,
        ),
      );
    }
  }

  Future<ApiResult<T>> post<T>(
    String path, {
    Map<String, dynamic>? data,
    T Function(dynamic)? fromJson,
  }) async {
    try {
      final response = await _dio.post(path, data: data);
      
      if (response.statusCode == 200) {
        if (fromJson != null) {
          final parsedData = fromJson(response.data);
          return ApiResult.success(parsedData);
        }
        return ApiResult.success(response.data as T);
      } else {
        return ApiResult.failure('HTTP Error: ${response.statusCode} - ${response.statusMessage}');
      }
    } on DioException catch (e) {
      String errorMessage;
      switch (e.type) {
        case DioExceptionType.connectionTimeout:
          errorMessage = 'Connection timeout';
          break;
        case DioExceptionType.sendTimeout:
          errorMessage = 'Send timeout';
          break;
        case DioExceptionType.receiveTimeout:
          errorMessage = 'Receive timeout';
          break;
        case DioExceptionType.badResponse:
          errorMessage = 'Server error: ${e.response?.statusCode}';
          break;
        case DioExceptionType.cancel:
          errorMessage = 'Request cancelled';
          break;
        case DioExceptionType.connectionError:
          errorMessage = 'Connection error';
          break;
        default:
          errorMessage = 'Network error: ${e.message}';
      }
      return ApiResult.failure(errorMessage);
    } catch (e) {
      return ApiResult.failure('Unexpected error: $e');
    }
  }

  Dio get dio => _dio;
}



