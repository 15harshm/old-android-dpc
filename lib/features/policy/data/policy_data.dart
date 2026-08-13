class PolicyData {
  final bool lockDevice;
  final bool cameraEnabled;
  final bool callsEnabled;
  final bool socialAppsEnabled;
  final bool wifiEnabled;
  final bool mobileDataEnabled;
  final bool usbDebuggingEnabled;
  final bool offlineLock;
  final bool factoryResetEnabled;
  final bool getSimInfo;
  final bool getLocation;

  const PolicyData({
    required this.lockDevice,
    required this.cameraEnabled,
    required this.callsEnabled,
    required this.socialAppsEnabled,
    required this.wifiEnabled,
    required this.mobileDataEnabled,
    required this.usbDebuggingEnabled,
    required this.offlineLock,
    required this.factoryResetEnabled,
    required this.getSimInfo,
    required this.getLocation,
  });

  factory PolicyData.fromJson(Map<String, dynamic> json) {
    return PolicyData(
      lockDevice: json['lock_device'] ?? false,
      cameraEnabled: json['camera_enabled'] ?? false,
      callsEnabled: json['calls_enabled'] ?? false,
      socialAppsEnabled: json['social_apps_enabled'] ?? false,
      wifiEnabled: json['wifi_enabled'] ?? false,
      mobileDataEnabled: json['mobile_data_enabled'] ?? false,
      usbDebuggingEnabled: json['usb_debugging_enabled'] ?? false,
      offlineLock: json['offline_lock'] ?? false,
      factoryResetEnabled: json['factory_reset_enabled'] ?? false,
      getSimInfo: json['get_sim_info'] ?? false,
      getLocation: json['get_location'] ?? false,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'lock_device': lockDevice,
      'camera_enabled': cameraEnabled,
      'calls_enabled': callsEnabled,
      'social_apps_enabled': socialAppsEnabled,
      'wifi_enabled': wifiEnabled,
      'mobile_data_enabled': mobileDataEnabled,
      'usb_debugging_enabled': usbDebuggingEnabled,
      'offline_lock': offlineLock,
      'factory_reset_enabled': factoryResetEnabled,
      'get_sim_info': getSimInfo,
      'get_location': getLocation,
    };
  }

  @override
  String toString() {
    return 'PolicyData('
        'lockDevice: $lockDevice, '
        'cameraEnabled: $cameraEnabled, '
        'callsEnabled: $callsEnabled, '
        'socialAppsEnabled: $socialAppsEnabled, '
        'wifiEnabled: $wifiEnabled, '
        'mobileDataEnabled: $mobileDataEnabled, '
        'usbDebuggingEnabled: $usbDebuggingEnabled, '
        'offlineLock: $offlineLock, '
        'factoryResetEnabled: $factoryResetEnabled, '
        'getSimInfo: $getSimInfo, '
        'getLocation: $getLocation'
        ')';
  }
}



