import 'package:flutter/services.dart';

/// Thin bridge to the native `com.renew.jss/admin` MethodChannel.
///
/// NOTE (running DPC / Device Admin): this class was trimmed to only the method
/// that is actually used at runtime — `getImei`. The former device-control methods
/// (lockDevice, wipeDevice, disableCamera, enterKiosk/exitKiosk, disableSettings,
/// disableCommunication, disableUsbDebugging, disableSocialApps, disableAllNetworks,
/// setWallpaper, getSimInfo, getLocation, activateAccount, applyFrp/removeFrp,
/// startService/stopService, …) were Device-Owner-heritage stubs with no native
/// handler (they returned `notImplemented`) and were only ever called from the
/// now-deleted, never-wired `lib/features/` enforcement layer. Real enforcement
/// runs natively via FCM → PolicyChangeProcessor → PolicyDispatcher; service
/// startup uses its own MethodChannel in main.dart / permission_setup_screen.dart.
class DpcApi {
  static const MethodChannel _channel = MethodChannel("com.renew.jss/admin");

  static Future<Map<String, String?>?> getImei() async {
    try {
      final data = await _channel.invokeMethod("getImei");
      if (data == null) return null;

      if (data is Map) {
        return Map<String, String?>.from(data);
      } else if (data is String) {
        return {"imei1": data, "imei2": null};
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  /// Device hardware identity from native `android.os.Build`.
  /// Returns `{manufacturer, brand, model}` or null on failure.
  static Future<Map<String, String?>?> getDeviceInfo() async {
    try {
      final data = await _channel.invokeMethod("getDeviceInfo");
      if (data is Map) {
        return Map<String, String?>.from(data);
      }
      return null;
    } catch (_) {
      return null;
    }
  }
}
