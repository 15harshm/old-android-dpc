import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../features/dpc/dpc_api.dart';

/// Device Identity Service - Enterprise-Safe IMEI Management
/// 
/// Provides a single source of truth for device IMEI.
/// IMEI is fetched only once and persisted locally.
/// All API calls must use this service for IMEI.
class DeviceIdentityService {
  static const String _storageKey = 'device_imei';
  static String? _cachedImei;
  static bool _isInitialized = false;

  /// Initialize the service - must be called before any API calls
  /// Fetches IMEI only if not already stored locally
  static Future<void> initialize() async {
    if (_isInitialized) return;
    
    try {
      final prefs = await SharedPreferences.getInstance();
      
      // Check if IMEI already exists in storage
      final storedImei = prefs.getString(_storageKey);
      
      if (storedImei != null && storedImei.isNotEmpty) {
        _cachedImei = storedImei;
        if (kDebugMode) {
          print('📱 DeviceIdentity: Using stored IMEI: $storedImei');
        }
      } else {
        // First time - fetch and store IMEI
        await _fetchAndStoreImei();
      }
      
      _isInitialized = true;
      
    } catch (e) {
      if (kDebugMode) {
        print('❌ DeviceIdentity initialization failed: $e');
      }
      // Do NOT crash app - continue without IMEI
    }
  }

  /// Get device IMEI - single source of truth
  /// Returns stored IMEI or null if not available
  static Future<String?> getDeviceImei() async {
    if (!_isInitialized) {
      await initialize();
    }
    
    return _cachedImei;
  }

  /// Force refresh IMEI (for testing/emergency use only)
  /// This will overwrite stored IMEI with fresh fetch
  static Future<void> refreshImei() async {
    if (kDebugMode) {
      print('🔄 DeviceIdentity: Force refreshing IMEI...');
    }
    
    await _fetchAndStoreImei();
  }

  /// Clear stored IMEI (for testing/reset only)
  static Future<void> clearStoredImei() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_storageKey);
      _cachedImei = null;
      _isInitialized = false;
      
      if (kDebugMode) {
        print('🗑️ DeviceIdentity: Stored IMEI cleared');
      }
    } catch (e) {
      if (kDebugMode) {
        print('❌ Failed to clear stored IMEI: $e');
      }
    }
  }

  /// Internal method to fetch and store IMEI
  static Future<void> _fetchAndStoreImei() async {
    try {
      if (kDebugMode) {
        print('📱 DeviceIdentity: Fetching IMEI from native layer...');
      }
      
      // Fetch IMEIs from native layer
      final imeis = await DpcApi.getImei();
      
      if (imeis == null || imeis.isEmpty) {
        if (kDebugMode) {
          print('❌ DeviceIdentity: Failed to fetch IMEIs - null or empty');
        }
        return;
      }
      
      final imei = imeis['imei1'];
      
      if (imei == null || imei.isEmpty) {
        if (kDebugMode) {
          print('❌ DeviceIdentity: IMEI1 not available or empty');
          print('📱 Available IMEIs: $imeis');
        }
        return;
      }
      
      // Validate IMEI format (basic check)
      if (!_isValidImei(imei)) {
        if (kDebugMode) {
          print('❌ DeviceIdentity: Invalid IMEI format: $imei');
        }
        return;
      }
      
      // Store IMEI locally
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_storageKey, imei);
      _cachedImei = imei;
      
      if (kDebugMode) {
        print('✅ DeviceIdentity: IMEI stored successfully: $imei');
        print('💾 IMEI will persist across app restarts');
      }
      
    } catch (e) {
      if (kDebugMode) {
        print('❌ DeviceIdentity: Exception during IMEI fetch: $e');
        print('📍 Stack trace: ${StackTrace.current}');
      }
      // Do NOT crash app - continue without IMEI
    }
  }

  /// Basic IMEI validation (15 digits, numeric only)
  static bool _isValidImei(String imei) {
    if (imei.isEmpty) return false;
    if (imei.length != 15) return false;
    if (!imei.contains(RegExp(r'^[0-9]+$'))) return false;
    return true;
  }

  /// Debug method to show current IMEI status
  static Future<void> debugPrintImeiStatus() async {
    if (!kDebugMode) return;
    
    print('\n📱 DEVICE IDENTITY STATUS:');
    print('  Initialized: $_isInitialized');
    print('  Cached IMEI: ${_cachedImei ?? "null"}');
    
    try {
      final prefs = await SharedPreferences.getInstance();
      final storedImei = prefs.getString(_storageKey);
      print('  Stored IMEI: ${storedImei ?? "null"}');
      print('  Storage Key: $_storageKey');
    } catch (e) {
      print('  Storage Error: $e');
    }
    
    print('');
  }

  /// Check if service has a valid IMEI
  static bool hasValidImei() {
    return _isInitialized && _cachedImei != null && _cachedImei!.isNotEmpty;
  }
}



