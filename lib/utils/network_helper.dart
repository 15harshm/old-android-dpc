import 'dart:io';
import 'package:flutter/foundation.dart';

class NetworkHelper {
  static Future<bool> isNetworkAvailable() async {
    try {
      // Check for internet connectivity
      final result = await InternetAddress.lookup('google.com');
      if (result.isNotEmpty && result[0].rawAddress.isNotEmpty) {
        return true;
      }
      return false;
    } catch (e) {
      if (kDebugMode) {
        print('🌐 Network check failed: $e');
      }
      return false;
    }
  }

  static Future<bool> isApiReachable() async {
    try {
      // Check if our API server is reachable
      final result = await InternetAddress.lookup('paynlocker.com');
      if (result.isNotEmpty && result[0].rawAddress.isNotEmpty) {
        return true;
      }
      return false;
    } catch (e) {
      if (kDebugMode) {
        print('🌐 API server check failed: $e');
      }
      return false;
    }
  }

  static Future<void> waitForNetwork({int maxRetries = 3, int delayMs = 2000}) async {
    int retries = 0;
    while (retries < maxRetries) {
      if (await isNetworkAvailable()) {
        if (kDebugMode) {
          print('🌐 Network available after $retries retries');
        }
        return;
      }
      retries++;
      if (retries < maxRetries) {
        await Future.delayed(Duration(milliseconds: delayMs));
      }
    }
    if (kDebugMode) {
      print('🌐 Network not available after $maxRetries retries');
    }
  }

  static void logNetworkStatus(String operation) {
    if (kDebugMode) {
      print('🌐 Network operation: $operation');
    }
  }
}
