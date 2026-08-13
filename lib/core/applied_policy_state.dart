import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/foundation.dart';

// STEP 1: AppliedPolicyState Model
class AppliedPolicyStateModel {
  final bool lockApplied;
  final bool cameraApplied;
  final bool callsApplied;
  final bool socialAppsApplied;
  final bool wifiApplied;
  final bool mobileDataApplied;
  final bool usbDebuggingApplied;
  final bool factoryResetApplied;
  final bool simInfoApplied;
  final bool locationApplied;

  const AppliedPolicyStateModel({
    this.lockApplied = false,
    this.cameraApplied = false,
    this.callsApplied = false,
    this.socialAppsApplied = false,
    this.wifiApplied = false,
    this.mobileDataApplied = false,
    this.usbDebuggingApplied = false,
    this.factoryResetApplied = false,
    this.simInfoApplied = false,
    this.locationApplied = false,
  });

  AppliedPolicyStateModel copyWith({
    bool? lockApplied,
    bool? cameraApplied,
    bool? callsApplied,
    bool? socialAppsApplied,
    bool? wifiApplied,
    bool? mobileDataApplied,
    bool? usbDebuggingApplied,
    bool? factoryResetApplied,
    bool? simInfoApplied,
    bool? locationApplied,
  }) {
    return AppliedPolicyStateModel(
      lockApplied: lockApplied ?? this.lockApplied,
      cameraApplied: cameraApplied ?? this.cameraApplied,
      callsApplied: callsApplied ?? this.callsApplied,
      socialAppsApplied: socialAppsApplied ?? this.socialAppsApplied,
      wifiApplied: wifiApplied ?? this.wifiApplied,
      mobileDataApplied: mobileDataApplied ?? this.mobileDataApplied,
      usbDebuggingApplied: usbDebuggingApplied ?? this.usbDebuggingApplied,
      factoryResetApplied: factoryResetApplied ?? this.factoryResetApplied,
      simInfoApplied: simInfoApplied ?? this.simInfoApplied,
      locationApplied: locationApplied ?? this.locationApplied,
    );
  }

  @override
  String toString() {
    return 'AppliedPolicyStateModel('
        'lockApplied: $lockApplied, '
        'cameraApplied: $cameraApplied, '
        'callsApplied: $callsApplied, '
        'socialAppsApplied: $socialAppsApplied, '
        'wifiApplied: $wifiApplied, '
        'mobileDataApplied: $mobileDataApplied, '
        'usbDebuggingApplied: $usbDebuggingApplied, '
        'factoryResetApplied: $factoryResetApplied, '
        'simInfoApplied: $simInfoApplied, '
        'locationApplied: $locationApplied'
        ')';
  }
}

// STEP 2: Persist AppliedPolicyState Locally
class AppliedPolicyState {
  static const String _keyLockApplied = 'lock_applied';
  static const String _keyCameraApplied = 'camera_applied';
  static const String _keyCallsApplied = 'calls_applied';
  static const String _keySocialAppsApplied = 'social_apps_applied';
  static const String _keyWifiApplied = 'wifi_applied';
  static const String _keyMobileDataApplied = 'mobile_data_applied';
  static const String _keyUsbDebuggingApplied = 'usb_debugging_applied';
  static const String _keyFactoryResetApplied = 'factory_reset_applied';
  static const String _keySimInfoApplied = 'sim_info_applied';
  static const String _keyLocationApplied = 'location_applied';

  static SharedPreferences? _prefs;

  // STEP 2: Load state on app start
  static Future<void> initialize() async {
    _prefs ??= await SharedPreferences.getInstance();
    if (kDebugMode) {
      print('🔄 AppliedPolicyState initialized');
    }
  }

  static Future<void> _ensureInitialized() async {
    if (_prefs == null) {
      await initialize();
    }
  }

  // STEP 2: Get complete applied state model
  static Future<AppliedPolicyStateModel> getAppliedState() async {
    await _ensureInitialized();
    
    return AppliedPolicyStateModel(
      lockApplied: _prefs?.getBool(_keyLockApplied) ?? false,
      cameraApplied: _prefs?.getBool(_keyCameraApplied) ?? false,
      callsApplied: _prefs?.getBool(_keyCallsApplied) ?? false,
      socialAppsApplied: _prefs?.getBool(_keySocialAppsApplied) ?? false,
      wifiApplied: _prefs?.getBool(_keyWifiApplied) ?? false,
      mobileDataApplied: _prefs?.getBool(_keyMobileDataApplied) ?? false,
      usbDebuggingApplied: _prefs?.getBool(_keyUsbDebuggingApplied) ?? false,
      factoryResetApplied: _prefs?.getBool(_keyFactoryResetApplied) ?? false,
      simInfoApplied: _prefs?.getBool(_keySimInfoApplied) ?? false,
      locationApplied: _prefs?.getBool(_keyLocationApplied) ?? false,
    );
  }

  // STEP 2: Save complete applied state model
  static Future<void> setAppliedState(AppliedPolicyStateModel state) async {
    await _ensureInitialized();
    
    await _prefs?.setBool(_keyLockApplied, state.lockApplied);
    await _prefs?.setBool(_keyCameraApplied, state.cameraApplied);
    await _prefs?.setBool(_keyCallsApplied, state.callsApplied);
    await _prefs?.setBool(_keySocialAppsApplied, state.socialAppsApplied);
    await _prefs?.setBool(_keyWifiApplied, state.wifiApplied);
    await _prefs?.setBool(_keyMobileDataApplied, state.mobileDataApplied);
    await _prefs?.setBool(_keyUsbDebuggingApplied, state.usbDebuggingApplied);
    await _prefs?.setBool(_keyFactoryResetApplied, state.factoryResetApplied);
    await _prefs?.setBool(_keySimInfoApplied, state.simInfoApplied);
    await _prefs?.setBool(_keyLocationApplied, state.locationApplied);
    
    if (kDebugMode) {
      print('💾 AppliedPolicyState saved: $state');
    }
  }

  // Individual getters/setters for backward compatibility
  static Future<bool> isLockApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keyLockApplied) ?? false;
  }

  static Future<void> setLockApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keyLockApplied, applied);
    if (kDebugMode) {
      print('📱 Lock applied state set to: $applied');
    }
  }

  static Future<bool> isCameraApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keyCameraApplied) ?? false;
  }

  static Future<void> setCameraApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keyCameraApplied, applied);
    if (kDebugMode) {
      print('📱 Camera applied state set to: $applied');
    }
  }

  static Future<bool> isCallsApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keyCallsApplied) ?? false;
  }

  static Future<void> setCallsApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keyCallsApplied, applied);
    if (kDebugMode) {
      print('📱 Calls applied state set to: $applied');
    }
  }

  static Future<bool> isSocialAppsApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keySocialAppsApplied) ?? false;
  }

  static Future<void> setSocialAppsApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keySocialAppsApplied, applied);
    if (kDebugMode) {
      print('📱 Social apps applied state set to: $applied');
    }
  }

  static Future<bool> isWifiApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keyWifiApplied) ?? false;
  }

  static Future<void> setWifiApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keyWifiApplied, applied);
    if (kDebugMode) {
      print('📱 WiFi applied state set to: $applied');
    }
  }

  static Future<bool> isMobileDataApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keyMobileDataApplied) ?? false;
  }

  static Future<void> setMobileDataApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keyMobileDataApplied, applied);
    if (kDebugMode) {
      print('📱 Mobile data applied state set to: $applied');
    }
  }

  static Future<bool> isUsbDebuggingApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keyUsbDebuggingApplied) ?? false;
  }

  static Future<void> setUsbDebuggingApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keyUsbDebuggingApplied, applied);
    if (kDebugMode) {
      print('📱 USB debugging applied state set to: $applied');
    }
  }

  static Future<bool> isFactoryResetApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keyFactoryResetApplied) ?? false;
  }

  static Future<void> setFactoryResetApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keyFactoryResetApplied, applied);
    if (kDebugMode) {
      print('📱 Factory reset applied state set to: $applied');
    }
  }

  static Future<bool> isSimInfoApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keySimInfoApplied) ?? false;
  }

  static Future<void> setSimInfoApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keySimInfoApplied, applied);
    if (kDebugMode) {
      print('📱 SIM info applied state set to: $applied');
    }
  }

  static Future<bool> isLocationApplied() async {
    await _ensureInitialized();
    return _prefs?.getBool(_keyLocationApplied) ?? false;
  }

  static Future<void> setLocationApplied(bool applied) async {
    await _ensureInitialized();
    await _prefs?.setBool(_keyLocationApplied, applied);
    if (kDebugMode) {
      print('📱 Location applied state set to: $applied');
    }
  }

  // Debug method to print all applied states
  static Future<void> debugPrintAllStates() async {
    if (!kDebugMode) return;
    
    final state = await getAppliedState();
    print('📋 APPLIED POLICY STATES:');
    print('  Lock Device: ${state.lockApplied}');
    print('  Camera: ${state.cameraApplied}');
    print('  Calls: ${state.callsApplied}');
    print('  Social Apps: ${state.socialAppsApplied}');
    print('  WiFi: ${state.wifiApplied}');
    print('  Mobile Data: ${state.mobileDataApplied}');
    print('  USB Debugging: ${state.usbDebuggingApplied}');
    print('  Factory Reset: ${state.factoryResetApplied}');
    print('  SIM Info: ${state.simInfoApplied}');
    print('  Location: ${state.locationApplied}');
  }

  // Reset all applied states (for testing)
  static Future<void> resetAllStates() async {
    await _ensureInitialized();
    
    await _prefs?.remove(_keyLockApplied);
    await _prefs?.remove(_keyCameraApplied);
    await _prefs?.remove(_keyCallsApplied);
    await _prefs?.remove(_keySocialAppsApplied);
    await _prefs?.remove(_keyWifiApplied);
    await _prefs?.remove(_keyMobileDataApplied);
    await _prefs?.remove(_keyUsbDebuggingApplied);
    await _prefs?.remove(_keyFactoryResetApplied);
    await _prefs?.remove(_keySimInfoApplied);
    await _prefs?.remove(_keyLocationApplied);
    
    if (kDebugMode) {
      print('🔄 All applied policy states reset');
    }
  }
}



