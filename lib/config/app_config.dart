import 'package:flutter/services.dart';
import 'flavor_registry.g.dart';

class AppConfig {
  static String appName = 'My Device';
  static String domain = 'myshopmypoint.com';
  static String apiBaseUrl = 'https://$domain/api2';
  static String appDescription =
      'My Shop My Point DPC - Required for monitoring app usage and device control';
  static String currentFlavor = 'default';

  // Localized Terms
  static String currencySymbol = '₹';
  static String paymentTerm = 'EMI';

  // The resolved registry entry for the active flavor. Null until initialize()
  // has run (before that, the static defaults above apply — the myshopmypoint brand).
  static FlavorEntry? _entry;

  /// Premium (FastEmi) UI gate — replaces the copy-pasted 5-flavor OR-chains.
  static bool get usesFastEmiUi => _entry?.usesFastEmiUi ?? false;

  /// Whether to notify the server of enrollment updates (was the eplocker check).
  static bool get notifyEnrollUpdate => _entry?.notifyEnrollUpdate ?? false;

  static const MethodChannel _channel = MethodChannel('com.renew.jss/admin');

  static Future<void> initialize() async {
    try {
      final String? flavor = await _channel.invokeMethod<String>('getFlavor');

      if (flavor != null) {
        currentFlavor = flavor;
        // Single source of truth: lib/config/flavor_registry.g.dart (generated
        // from tool/flavors/flavors.yaml). A missing/unknown flavor falls back
        // to the myshopmypoint brand rather than silently shipping garbage.
        final entry = kFlavorRegistry[flavor] ?? kFlavorRegistry['myshopmypoint']!;
        _entry = entry;
        appName = entry.appName;
        domain = entry.domain;
        appDescription = entry.appDescription;
        currencySymbol = entry.currency;
        paymentTerm = entry.term;
        apiBaseUrl = 'https://${entry.domain}/api2';
      }
    } catch (e) {
      // Fallback to default
      print('Failed to get flavor: $e');
    }
  }
}
