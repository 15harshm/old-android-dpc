import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:emi_locker_dpc/services/policy_api_service.dart';
import 'package:emi_locker_dpc/services/imei_service.dart';
import 'package:emi_locker_dpc/widgets/imei_input_screen.dart';
import 'package:emi_locker_dpc/widgets/permission_setup_screen.dart';
import 'package:emi_locker_dpc/widgets/main_screen.dart';
import 'config/app_config.dart';
import 'config/fastemi_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppConfig.initialize();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: AppConfig.appName,
      theme: AppConfig.usesFastEmiUi
          ? FastEmiTheme.themeData
          : ThemeData(primarySwatch: Colors.blue, fontFamily: 'Roboto'),
      home: const HomePage(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  Map<String, dynamic>? deviceInfo;
  bool isLoadingDeviceInfo = false;
  bool _showImeiInput = false;
  bool _showPermissionSetup = false;
  // Opt #10: Cache setup completion in-memory so didChangeAppLifecycleState
  // never needs to touch SharedPreferences (async disk read) on every resume.
  bool _setupCompleted = false;
  final TextEditingController _imei1Controller = TextEditingController();
  final TextEditingController _imei2Controller = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkImeiSetup();
  }

  /// CHECK BOTH IMEI AND PERMISSIONS - ATOMIC OPERATION
  /// This ensures the UI shows the correct screen based on both conditions
  /// Returns early to prevent starting services before permission screen
  Future<void> _checkImeiSetup() async {
    try {
      print('RunningDPC: Starting IMEI and permission check...');

      // Step 1: Check IMEI
      final deviceImeis = await ImeiService.getDeviceImeis();
      final isImeiSaved = await ImeiService.isImeiSaved();

      if (!isImeiSaved ||
          deviceImeis == null ||
          deviceImeis!['imei1'] == null ||
          deviceImeis!['imei2'] == null) {
        setState(() {
          _showImeiInput = true;
          _showPermissionSetup = false;
          _imei1Controller.text = deviceImeis?['imei1'] ?? '';
          _imei2Controller.text = deviceImeis?['imei2'] ?? '';
        });
        print('RunningDPC: IMEI not saved - showing IMEI screen');
        return; // STOP HERE - don't proceed further
      }

      // Step 2: IMEI is saved. The permission screen is a ONE-TIME enrollment step.
      // It must appear only until enrollment completes (user presses Continue) and
      // NEVER again afterwards — even if a live permission check reports something as
      // not-granted (battery-optimization / accessibility often read back as false
      // after a relaunch). So the screen is gated purely on the persisted
      // 'permissions_setup_completed' flag, not on a live permission re-check.
      final prefs = await SharedPreferences.getInstance();
      final setupCompleted =
          prefs.getBool('permissions_setup_completed') ?? false;

      if (!setupCompleted) {
        print('RunningDPC: Enrollment not completed - showing permission screen');
        setState(() {
          _showImeiInput = false;
          _showPermissionSetup = true;
        });
        return; // STOP HERE - don't start services until enrollment completes
      }

      // Enrollment already completed -> ALWAYS the main screen from here on.
      _setupCompleted = true; // keep in-memory cache in sync for the resume guard
      print('RunningDPC: Enrollment completed - showing main screen');
      setState(() {
        _showImeiInput = false;
        _showPermissionSetup = false;
      });

      // Auto-start services on (re)launch. Best-effort: a failure here must NOT
      // bounce the user back to the permission screen — enrollment is already done.
      // ✅ ANR FIX: Run all service-start MethodChannel calls in PARALLEL.
      // Opt #4: the 4 no-op calls (initializeFCM etc.) were already removed.
      try {
        const platform = MethodChannel('com.renew.jss/admin');
        await Future.wait([
          platform.invokeMethod('startService'),
          platform.invokeMethod('startBackgroundMonitoring'),
          platform.invokeMethod('startLocationMonitoring'),
          platform.invokeMethod('applyAdminStartup'),
        ]).catchError((e) {
          print('RunningDPC: Non-fatal error during parallel service start: $e');
          return <dynamic>[];
        });
      } catch (e) {
        print('RunningDPC: Error starting services (non-fatal): $e');
      }

      _loadDeviceInfo();
    } catch (e) {
      print('RunningDPC: Error in _checkImeiSetup: $e');
      setState(() {
        _showImeiInput = true;
        _showPermissionSetup = false;
      });
    }
  }

  Future<void> _saveImei() async {
    if (_imei1Controller.text.isEmpty || _imei2Controller.text.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Please enter both IMEI 1 and IMEI 2'),
          backgroundColor: Colors.red,
        ),
      );
      return;
    }

    try {
      await ImeiService.saveUserImei(
        _imei1Controller.text,
        _imei2Controller.text,
      );

      // Show permission screen
      setState(() {
        _showImeiInput = false;
        _showPermissionSetup = true;
      });

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('IMEI saved successfully'),
          backgroundColor: Colors.green,
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Error saving IMEI: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _onAllPermissionsGranted() async {
    print('RunningDPC: All permissions granted - closing permission screen');

    // Enroll device hardware identity (manufacturer/brand/model) to the
    // flavor-specific /api2/deviceEnroll.php. Runs for EVERY flavor the moment
    // the user grants all permissions and taps Continue. Fire-and-forget so it
    // never blocks closing the permission screen / starting services.
    () async {
      try {
        final ok = await PolicyApiService.enrollDeviceInfo();
        print('RunningDPC: deviceEnroll ${ok ? 'succeeded' : 'failed'}');
      } catch (e) {
        print('RunningDPC: Error during deviceEnroll call: $e');
      }
    }();

    // For flavors that opt into enroll-update notification, notify the server
    // when setup starts after permissions (driven by the registry, not a name literal).
    if (AppConfig.notifyEnrollUpdate) {
      () async {
        try {
          final savedImei = await ImeiService.getSavedImei();
          final imei = savedImei?['imei1'] ?? _imei1Controller.text;
          if (imei.isNotEmpty) {
            print(
              'RunningDPC: eplocker setup started, invoking isEnrollUpdate for IMEI: $imei',
            );
            await PolicyApiService.isEnrollUpdate(imei);
          } else {
            print(
              'RunningDPC: IMEI is empty, cannot invoke isEnrollUpdate API',
            );
          }
        } catch (e) {
          print('RunningDPC: Error during isEnrollUpdate call: $e');
        }
      }();
    }

    try {
      const platform = MethodChannel('com.renew.jss/admin');
      // ✅ ANR FIX: Run all service-start MethodChannel calls in PARALLEL.
      // Sequential `await` blocks the Android platform thread for each call.
      // Opt #4: Removed 4 no-op calls — see _checkImeiSetup for details.
      await Future.wait([
        platform.invokeMethod('startService'),
        platform.invokeMethod('startNotificationService'),
        platform.invokeMethod('startBackgroundMonitoring'),
        platform.invokeMethod('startLocationMonitoring'),
        platform.invokeMethod('applyAdminStartup'),
      ]).catchError((e) {
        print('RunningDPC: Non-fatal error during parallel service start: $e');
        return <dynamic>[];
      });
      print('RunningDPC: All services started');

    } catch (e) {
      print('RunningDPC: Error starting services: $e');
    }

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('permissions_setup_completed', true);
      // Opt #10: Keep in-memory cache in sync so resume guard is instant.
      _setupCompleted = true;
      print('RunningDPC: Saved permissions_setup_completed = true to SharedPreferences');
    } catch (e) {
      print('RunningDPC: Error saving setup completion flag: $e');
    }

    setState(() {
      _showPermissionSetup = false;
    });

    _loadDeviceInfo();
  }

  Future<void> _loadDeviceInfo() async {
    if (_showImeiInput) return;

    setState(() {
      isLoadingDeviceInfo = true;
    });

    try {
      final savedImei = await ImeiService.getSavedImei();

      if (savedImei != null) {
        final info = await PolicyApiService.fetchDeviceInfoWithDeviceImei(
          imei1: savedImei['imei1'],
          imei2: savedImei['imei2'],
        );

        if (info != null) {
          setState(() {
            deviceInfo = {
              'customerName': info.customer.customerName,
              'retailerName': info.user.userName,
              'retailerPhone': info.user.phone,
              'deviceModel': '${info.customer.brand} ${info.customer.model}',
              'totalEmi': info.emiSummary.totalEmi.toString(),
              'pendingEmi': int.tryParse(info.emiSummary.pendingEmi) ?? 0,
            };
            isLoadingDeviceInfo = false;

            // 🚀 SYNC: Save retailer phone to native authorized store for offline locking
            if (info.user.phone.isNotEmpty) {
              const platform = MethodChannel('com.renew.jss/admin');
              platform.invokeMethod('saveRetailerPhone', {
                'phone': info.user.phone,
              });
              print(
                'RunningDPC: Syncing retailer phone with native store: ${info.user.phone}',
              );
            }
          });
          print('RunningDPC: Device info loaded successfully');
        }
      }
    } catch (e) {
      setState(() {
        isLoadingDeviceInfo = false;
      });
      print('RunningDPC: Device info fetch error: $e');
    }
  }

  Future<void> _removeAllRestrictions() async {
    try {
      const platform = MethodChannel('com.renew.jss/admin');
      await platform.invokeMethod('removeAllRestrictions');

      try {
        final prefs = await SharedPreferences.getInstance();
        await prefs.remove('permissions_setup_completed');
        print('RunningDPC: Cleared permissions_setup_completed flag');
      } catch (e) {
        print('RunningDPC: Error clearing setup completion flag: $e');
      }

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('All restrictions removed successfully'),
          backgroundColor: Colors.green,
        ),
      );
      print('RunningDPC: All restrictions removed successfully');
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Error removing restrictions: $e'),
          backgroundColor: Colors.red,
        ),
      );
      print('RunningDPC: Error removing restrictions: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_showImeiInput) {
      return Scaffold(
        backgroundColor: const Color(0xFFF8F9FA),
        body: SafeArea(
          child: ImeiInputScreen(
            imei1Controller: _imei1Controller,
            imei2Controller: _imei2Controller,
            onSave: _saveImei,
          ),
        ),
      );
    }

    if (_showPermissionSetup) {
      return PermissionSetupScreen(
        onAllPermissionsGranted: _onAllPermissionsGranted,
      );
    }

    return MainScreen(
      deviceInfo: deviceInfo,
      onRemoveRestrictions: _removeAllRestrictions,
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);

    // CRITICAL FIX: When app returns from background, check permissions again
    // This is necessary because Device Admin apps keep the process alive
    // So initState() doesn't run again on app resume - this lifecycle callback does
    if (state == AppLifecycleState.resumed) {
      print('RunningDPC: App resumed from background - re-checking permissions');
      // Opt #10: Use cached bool — no async SharedPreferences read on every resume.
      if (!_setupCompleted) {
        _checkImeiSetup();
      } else {
        print('RunningDPC: Setup already completed — skipping full re-check on resume');
      }
    }
  }
}
