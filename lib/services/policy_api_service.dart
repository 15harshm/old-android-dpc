import 'package:flutter/foundation.dart';
import '../core/api_client.dart';
import '../core/device_identity_service.dart';
import '../core/locked_device_data.dart';
import '../core/device_info_response.dart';
import '../features/policy/data/policy_data.dart';
import '../features/policy/domain/policy_enforcement_manager.dart';
import '../features/policy/domain/policy_diff_engine.dart';
import '../features/dpc/dpc_api.dart';
import 'imei_service.dart';

class PolicyApiService {
  static final ApiClient _apiClient = ApiClient();
  static bool _initialized = false;

  static void _ensureInitialized() {
    if (!_initialized) {
      _apiClient.initialize();
      // BlockCheck API does not require Bearer token authentication
      _initialized = true;
    }
  }

  // Phase 2: Initialize enforcement manager and device identity
  static Future<void> initializePhase2() async {
    _ensureInitialized();

    // Initialize device identity first (IMEI persistence)
    await DeviceIdentityService.initialize();

    await PolicyEnforcementManager.initialize();

    if (kDebugMode) {
      print('🚀 Phase 2: Policy Enforcement and Device Identity initialized');
      await DeviceIdentityService.debugPrintImeiStatus();
    }
  }

  static Future<PolicyData?> fetchPolicy(String imei) async {
    _ensureInitialized();

    try {
      if (kDebugMode) {
        print('Fetching policy for IMEI: $imei');
      }

      final result = await _apiClient.post<Map<String, dynamic>>(
        '/BlockCheck.php',
        data: {'IMEI': imei},
      );

      if (result.success && result.data != null) {
        final responseData = result.data!;

        if (responseData['status'] == true && responseData['data'] != null) {
          final policyData = PolicyData.fromJson(responseData['data']);
          if (kDebugMode) {
            print('Policy data parsed successfully: $policyData');
          }
          return policyData;
        } else {
          if (kDebugMode) {
            print('API returned status: false or no data');
          }
          return null;
        }
      } else {
        if (kDebugMode) {
          print('API request failed: ${result.error}');
        }
        return null;
      }
    } catch (e) {
      if (kDebugMode) {
        print('Exception during policy fetch: $e');
      }
      return null;
    }
  }

  static Future<PolicyData?> fetchPolicyWithDeviceImei() async {
    try {
      if (kDebugMode) {
        print('🔌 Phase 1: Fetching policy with persisted device IMEI...');
      }

      // Get IMEI from DeviceIdentityService (persisted or fetched once)
      final imei = await DeviceIdentityService.getDeviceImei();

      if (imei == null || imei.isEmpty) {
        if (kDebugMode) {
          print(
            '❌ Failed to get device IMEI - DeviceIdentityService returned null/empty',
          );
          print('💡 This may happen on first launch or if IMEI fetch failed');
        }
        return null;
      }

      if (kDebugMode) {
        print('🎯 Using persisted IMEI for policy fetch: $imei');
        print('🌐 Calling fetchPolicy with IMEI: $imei');
      }

      final policy = await fetchPolicy(imei);

      if (kDebugMode) {
        if (policy != null) {
          print('✅ Phase 1: Policy fetch successful');
          print('📋 Policy data: $policy');
        } else {
          print('❌ Phase 1: Policy fetch returned null');
        }
      }

      return policy;
    } catch (e) {
      if (kDebugMode) {
        print('❌ Phase 1: Exception during policy fetch: $e');
        print('📍 Stack trace: ${StackTrace.current}');
      }
      return null;
    }
  }

  // TEMPORARY: EMULATOR TESTING ONLY
  // Hardcoded IMEI for emulator testing - DO NOT USE IN PRODUCTION
  static Future<PolicyData?> fetchPolicyWithEmulatorImei() async {
    const String emulatorImei = '951357456852159'; // TEMPORARY TEST IMEI

    if (kDebugMode) {
      print('🧪 USING EMULATOR IMEI OVERRIDE: $emulatorImei');
      print('⚠️  THIS IS FOR EMULATOR TESTING ONLY');
    }

    return await fetchPolicy(emulatorImei);
  }

  // Phase 2: Fetch and analyze policy decisions (NO ENFORCEMENT YET)
  static Future<PolicyData?> fetchAndAnalyzePolicy(String imei) async {
    await initializePhase2();

    final policy = await fetchPolicy(imei);
    if (policy != null) {
      if (kDebugMode) {
        print('🎯 Phase 2: Analyzing policy decisions (NO ENFORCEMENT YET)...');
      }

      // STEP 4: Integrate Diff Logic with 1-Minute Fetch
      // Only log decisions, do NOT enforce anything yet
      await PolicyDiffEngine.analyzeAndLogDecisions(policy);

      if (kDebugMode) {
        print('🎯 Phase 2: Policy analysis complete (DECISIONS ONLY)');
      }
    }

    return policy;
  }

  // Phase 2: Fetch and analyze policy decisions with emulator IMEI
  static Future<PolicyData?> fetchAndAnalyzePolicyWithEmulatorImei() async {
    const String emulatorImei = '951357456852159'; // TEMPORARY TEST IMEI

    if (kDebugMode) {
      print('🧪 Phase 2: USING EMULATOR IMEI OVERRIDE: $emulatorImei');
      print('⚠️  THIS IS FOR EMULATOR TESTING ONLY');
    }

    return await fetchAndAnalyzePolicy(emulatorImei);
  }

  // Phase 2: Debug method to show current states and decisions
  static Future<void> debugPhase2Decisions() async {
    if (!kDebugMode) return;

    await initializePhase2();

    print('\n🎯 PHASE 2 DEBUG STATES:');
    print('Policy enforcement handled by native Android side via socket/FCM');
    print('');
  }

  // Fetch locked device details for kiosk screen
  static Future<LockedDeviceData?> fetchLockedDeviceDetails(String imei) async {
    _ensureInitialized();

    try {
      if (kDebugMode) {
        print('Fetching locked device details for IMEI: $imei');
      }

      final result = await _apiClient.post<Map<String, dynamic>>(
        '/LockedDeviceDesc.php',
        data: {'imei': imei},
      );

      if (result.success && result.data != null) {
        final responseData = result.data!;

        if (responseData['status'] == 'success' &&
            responseData['data'] != null) {
          final deviceData = LockedDeviceData.fromJson(responseData['data']);
          if (kDebugMode) {
            print('Locked device data fetched successfully: $deviceData');
          }
          return deviceData;
        } else {
          if (kDebugMode) {
            print('API returned status: ${responseData['status']} or no data');
          }
          return null;
        }
      } else {
        if (kDebugMode) {
          print('API request failed: ${result.error}');
        }
        return null;
      }
    } catch (e) {
      if (kDebugMode) {
        print('Exception during locked device details fetch: $e');
      }
      return null;
    }
  }

  // Fetch locked device details using device IMEI
  static Future<LockedDeviceData?>
  fetchLockedDeviceDetailsWithDeviceImei() async {
    try {
      if (kDebugMode) {
        print('🔒 Fetching locked device details with device IMEI...');
      }

      // Get IMEI from DeviceIdentityService
      final imei = await DeviceIdentityService.getDeviceImei();

      if (imei == null || imei.isEmpty) {
        if (kDebugMode) {
          print('❌ Failed to get device IMEI for locked device details');
        }
        return null;
      }

      if (kDebugMode) {
        print('🎯 Using IMEI for locked device details fetch: $imei');
      }

      final deviceData = await fetchLockedDeviceDetails(imei);

      if (kDebugMode) {
        if (deviceData != null) {
          print('✅ Locked device details fetch successful');
          print('👤 User data: $deviceData');
        } else {
          print('❌ Locked device details fetch returned null');
        }
      }

      return deviceData;
    } catch (e) {
      if (kDebugMode) {
        print('❌ Exception during locked device details fetch: $e');
      }
      return null;
    }
  }

  // Fetch device info for EMI details
  static Future<DeviceInfoResponse?> fetchDeviceInfo(String imei) async {
    _ensureInitialized();

    try {
      if (kDebugMode) {
        print('Fetching device info for IMEI: $imei');
      }

      final result = await _apiClient.post<Map<String, dynamic>>(
        '/DeviceInfo.php',
        data: {'imei': imei},
      );

      if (result.success && result.data != null) {
        final responseData = result.data!;

        if (responseData['status'] == true) {
          final deviceInfo = DeviceInfoResponse.fromJson(responseData);
          if (kDebugMode) {
            print('Device info fetched successfully');
            print('Customer: ${deviceInfo.customer.customerName}');
            print(
              'EMI Summary: ${deviceInfo.emiSummary.totalEmi} total, ${deviceInfo.emiSummary.pendingEmi} pending',
            );
          }
          return deviceInfo;
        } else {
          if (kDebugMode) {
            print('API returned status: ${responseData['status']}');
          }
          return null;
        }
      } else {
        if (kDebugMode) {
          print('API request failed: ${result.error}');
        }
        return null;
      }
    } catch (e) {
      if (kDebugMode) {
        print('Exception during device info fetch: $e');
      }
      return null;
    }
  }

  // Fetch FRP data for device
  static Future<List<String>?> fetchFrpData(String imei) async {
    _ensureInitialized();

    try {
      if (kDebugMode) {
        print('Fetching FRP data for IMEI: $imei');
      }

      final result = await _apiClient.post<Map<String, dynamic>>(
        '/FrpData.php',
        data: {'imei': imei},
      );

      if (result.success && result.data != null) {
        final responseData = result.data!;

        if (responseData['status'] == true && responseData['data'] != null) {
          final data = responseData['data'];
          final gaiaIdsArray = data['gaia_ids'];

          if (gaiaIdsArray is List && gaiaIdsArray.isNotEmpty) {
            final gaiaIds = <String>[];
            for (final gaiaId in gaiaIdsArray) {
              if (gaiaId is String && gaiaId.isNotEmpty) {
                gaiaIds.add(gaiaId);
              }
            }

            if (kDebugMode) {
              print('FRP data fetched successfully: $gaiaIds');
            }
            return gaiaIds;
          } else {
            if (kDebugMode) {
              print('No valid gaia_ids found in API response');
            }
            return null;
          }
        } else {
          if (kDebugMode) {
            print('API returned status: false or no data');
          }
          return null;
        }
      } else {
        if (kDebugMode) {
          print('API request failed: ${result.error}');
        }
        return null;
      }
    } catch (e) {
      if (kDebugMode) {
        print('Exception during FRP data fetch: $e');
      }
      return null;
    }
  }

  // Fetch FRP data using device IMEI
  static Future<List<String>?> fetchFrpDataWithDeviceImei() async {
    try {
      if (kDebugMode) {
        print('?? Fetching FRP data with device IMEI...');
      }

      // Get IMEI from DeviceIdentityService
      final imei = await DeviceIdentityService.getDeviceImei();

      if (imei == null || imei.isEmpty) {
        if (kDebugMode) {
          print('? Failed to get device IMEI for FRP data');
        }
        return null;
      }

      if (kDebugMode) {
        print('?? Using IMEI for FRP data fetch: $imei');
      }

      final frpData = await fetchFrpData(imei);

      if (kDebugMode) {
        if (frpData != null) {
          print('? FRP data fetch successful: $frpData');
        } else {
          print('? FRP data fetch returned null');
        }
      }

      return frpData;
    } catch (e) {
      if (kDebugMode) {
        print('? Exception during FRP data fetch: $e');
      }
      return null;
    }
  }

  // Fetch device info using device IMEI from DPC API
  static Future<DeviceInfoResponse?> fetchDeviceInfoWithDeviceImei({
    String? imei1,
    String? imei2,
  }) async {
    try {
      if (kDebugMode) {
        print('📱 Fetching device info with device IMEI...');
      }

      String imei;

      if (imei1 != null && imei1.isNotEmpty) {
        // Use provided IMEI (from saved user input)
        imei = imei1;
        if (kDebugMode) {
          print('📱 Using provided IMEI: $imei');
        }
      } else {
        // Get IMEI from DPC API (fallback)
        final imeiData = await DpcApi.getImei();

        if (imeiData == null || imeiData.isEmpty) {
          if (kDebugMode) {
            print('❌ Failed to get device IMEI from DPC API');
          }
          return null;
        }

        // Use IMEI1 (primary IMEI)
        imei = imeiData['imei1'] ?? '';

        if (imei.isEmpty) {
          if (kDebugMode) {
            print('❌ IMEI1 is null or empty');
          }
          return null;
        }

        if (kDebugMode) {
          print('📱 Using DPC API IMEI: $imei');
        }
      }

      if (kDebugMode) {
        print('🎯 Using IMEI for device info fetch: $imei');
      }

      final deviceInfo = await fetchDeviceInfo(imei);

      if (kDebugMode) {
        if (deviceInfo != null) {
          print('✅ Device info fetch successful');
          print('👤 Customer: ${deviceInfo.customer.customerName}');
          print(
            '📊 EMI Summary: ${deviceInfo.emiSummary.totalEmi} total, ${deviceInfo.emiSummary.pendingEmi} pending',
          );
        } else {
          print('❌ Device info fetch returned null');
        }
      }

      return deviceInfo;
    } catch (e) {
      if (kDebugMode) {
        print('❌ Exception during device info fetch: $e');
      }
      return null;
    }
  }

  // Enroll device hardware identity to the flavor-specific
  // `https://{domain}/api2/deviceEnroll.php` endpoint. Called once, for every
  // flavor, right after the user grants all permissions and taps Continue.
  static Future<bool> deviceEnroll({
    required String manufacturer,
    required String brand,
    required String model,
    required String imei1,
  }) async {
    _ensureInitialized();
    try {
      if (kDebugMode) {
        print('📤 deviceEnroll -> manufacturer=$manufacturer, brand=$brand, model=$model, imei1=$imei1');
      }
      final result = await _apiClient.post<dynamic>(
        '/deviceEnroll.php',
        data: {
          'manufacturer': manufacturer,
          'brand': brand,
          'model': model,
          'imei1': imei1,
        },
      );
      if (kDebugMode) {
        print('deviceEnroll result: ${result.success}, data: ${result.data}');
      }
      return result.success;
    } catch (e) {
      if (kDebugMode) {
        print('Exception during deviceEnroll call: $e');
      }
      return false;
    }
  }

  // Convenience wrapper: read the device's manufacturer/brand/model from the
  // native layer, then POST them to deviceEnroll.php. Safe to fire-and-forget.
  static Future<bool> enrollDeviceInfo() async {
    try {
      final info = await DpcApi.getDeviceInfo();
      if (info == null) {
        if (kDebugMode) {
          print('❌ enrollDeviceInfo: native getDeviceInfo returned null');
        }
        return false;
      }
      final manufacturer = info['manufacturer'] ?? '';
      final brand = info['brand'] ?? '';
      final model = info['model'] ?? '';
      if (manufacturer.isEmpty && brand.isEmpty && model.isEmpty) {
        if (kDebugMode) {
          print('❌ enrollDeviceInfo: empty device info, skipping enroll');
        }
        return false;
      }

      // Resolve imei1: prefer the enrolled/saved value, fall back to the live
      // native IMEI (phone permission is granted by this point).
      String imei1 = '';
      final savedImei = await ImeiService.getSavedImei();
      imei1 = savedImei?['imei1'] ?? '';
      if (imei1.isEmpty) {
        final nativeImei = await DpcApi.getImei();
        imei1 = nativeImei?['imei1'] ?? '';
      }

      return await deviceEnroll(
        manufacturer: manufacturer,
        brand: brand,
        model: model,
        imei1: imei1,
      );
    } catch (e) {
      if (kDebugMode) {
        print('❌ Exception during enrollDeviceInfo: $e');
      }
      return false;
    }
  }

  // isEnrollUpdate API for eplocker flavor setup check
  static Future<bool> isEnrollUpdate(String imei) async {
    _ensureInitialized();
    try {
      if (kDebugMode) {
        print('Calling isEnrollUpdate for IMEI: $imei');
      }
      final result = await _apiClient.post<dynamic>(
        '/isEnrollUpdate.php',
        data: {'imei': imei},
      );
      if (kDebugMode) {
        print('isEnrollUpdate result: ${result.success}, data: ${result.data}');
      }
      return result.success;
    } catch (e) {
      if (kDebugMode) {
        print('Exception during isEnrollUpdate call: $e');
      }
      return false;
    }
  }
}
