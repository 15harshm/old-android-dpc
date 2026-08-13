import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

class ImeiService {
  static const platform = MethodChannel('com.renew.jss/admin');

  static Future<Map<String, String?>?> getDeviceImeis() async {
    try {
      final imeis = await platform.invokeMethod('getImei');
      print('RunningDPC: 📱 Raw device IMEIs from native: $imeis');
      
      final imeiMap = Map<String, String?>.from(imeis);
      print('RunningDPC: 📱 Processed IMEI map: $imeiMap');
      
      return imeiMap;
    } catch (e) {
      print('RunningDPC: Error getting device IMEIs: $e');
      // Return empty map on error to trigger IMEI input
      return {};
    }
  }

  static Future<bool> isImeiSaved() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedImei1 = prefs.getString('user_imei1');
      final savedImei2 = prefs.getString('user_imei2');
      return savedImei1 != null && savedImei2 != null;
    } catch (e) {
      print('Error checking if IMEI is saved: $e');
      return false;
    }
  }

  static Future<void> saveUserImei(String imei1, String imei2) async {
    try {
      await platform.invokeMethod('saveUserImei', {
        'imei1': imei1,
        'imei2': imei2,
      });

      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('user_imei1', imei1);
      await prefs.setString('user_imei2', imei2);
      
      print('RunningDPC: ✅ IMEI saved successfully: $imei1, $imei2');
    } catch (e) {
      print('RunningDPC: Error saving IMEI: $e');
      throw Exception('Failed to save IMEI: $e');
    }
  }

  static Future<Map<String, String>?> getSavedImei() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedImei1 = prefs.getString('user_imei1');
      final savedImei2 = prefs.getString('user_imei2');
      
      if (savedImei1 != null && savedImei2 != null) {
        return {
          'imei1': savedImei1,
          'imei2': savedImei2,
        };
      }
      return null;
    } catch (e) {
      print('Error getting saved IMEI: $e');
      return null;
    }
  }

  static Future<void> clearSavedImei() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove('user_imei1');
      await prefs.remove('user_imei2');
      print('RunningDPC: 🗑️ IMEI cleared from storage');
    } catch (e) {
      print('RunningDPC: Error clearing saved IMEI: $e');
    }
  }
}
