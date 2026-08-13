import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import '../config/app_config.dart';
import '../config/fastemi_theme.dart';

class PermissionSetupScreen extends StatefulWidget {
  final Future<void> Function() onAllPermissionsGranted;

  const PermissionSetupScreen({
    super.key,
    required this.onAllPermissionsGranted,
  });

  @override
  State<PermissionSetupScreen> createState() => _PermissionSetupScreenState();
}

class _PermissionSetupScreenState extends State<PermissionSetupScreen>
    with WidgetsBindingObserver, TickerProviderStateMixin {
  Map<String, bool> _permissionStatus = {};
  bool _isLoading = false;
  bool _isProcessingDeviceAdmin = false;
  bool _allPermissionsGranted = false;

  // Entrance animations for FastEMI
  late AnimationController _fadeCtrl;
  late AnimationController _slideCtrl;
  late Animation<double> _fadeAnim;
  late Animation<Offset> _slideAnim;

  final List<Map<String, dynamic>> _permissions = [
    {
      'name': 'Device Admin',
      'key': 'deviceAdmin',
      'icon': Icons.security,
      'description': 'Required for device management',
    },
    {
      'name': 'Battery Optimization',
      'key': 'batteryOptimization',
      'icon': Icons.battery_alert,
      'description': 'Required for background operation',
    },
    {
      'name': 'Overlay',
      'key': 'overlay',
      'icon': Icons.layers,
      'description': 'Required for displaying overlays',
    },
    {
      'name': 'Location',
      'key': 'location',
      'icon': Icons.location_on,
      'description': 'Required for location tracking',
    },
    {
      'name': 'SMS',
      'key': 'sms',
      'icon': Icons.sms,
      'description': 'Required for SMS monitoring',
    },
    {
      'name': 'Phone',
      'key': 'phone',
      'icon': Icons.phone,
      'description': 'Required for call monitoring',
    },
    {
      'name': 'Notification',
      'key': 'notification',
      'icon': Icons.notifications,
      'description': 'Required for showing notifications',
    },
    {
      'name': 'Accessibility',
      'key': 'accessibility',
      'icon': Icons.accessibility,
      'description': 'Required for app monitoring and security',
    },
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // Initialize animations for FastEMI
    _fadeCtrl = AnimationController(
      duration: const Duration(milliseconds: 900),
      vsync: this,
    );
    _slideCtrl = AnimationController(
      duration: const Duration(milliseconds: 800),
      vsync: this,
    );

    _fadeAnim = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _fadeCtrl, curve: Curves.easeOut),
    );
    _slideAnim = Tween<Offset>(
      begin: const Offset(0.0, 0.05),
      end: Offset.zero,
    ).animate(
      CurvedAnimation(parent: _slideCtrl, curve: Curves.easeOutCubic),
    );

    _fadeCtrl.forward();
    _slideCtrl.forward();

    _checkPermissions();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _fadeCtrl.dispose();
    _slideCtrl.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    if (state == AppLifecycleState.resumed) {
      print('RunningDPC: 🔄 App resumed - refreshing permission status');
      _checkPermissions();
    }
  }

  Future<void> _checkPermissions() async {
    setState(() {
      _isLoading = true;
    });

    try {
      const platform = MethodChannel('com.renew.jss/admin');
      final permissions = await platform.invokeMethod('checkAllPermissions');

      setState(() {
        _permissionStatus = Map<String, bool>.from(permissions);
        _allPermissionsGranted = _permissionStatus.values.every((granted) => granted);
        _isLoading = false;
      });

      print('RunningDPC: 🚨 Permission Status:');
      _permissionStatus.forEach((key, value) {
        print('RunningDPC:   $key: $value');
      });

      if (_allPermissionsGranted) {
        print('RunningDPC: ✅ All permissions granted! User can now click Continue button');
      } else {
        print('RunningDPC: ⚠️ Some permissions still pending');
      }
    } catch (e) {
      print('RunningDPC: Error checking permissions: $e');
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _requestPermission(String permissionKey) async {
    try {
      const platform = MethodChannel('com.renew.jss/admin');

      switch (permissionKey) {
        case 'deviceAdmin':
          print('RunningDPC: 🔄 Starting device admin permission request');
          setState(() {
            _isProcessingDeviceAdmin = true;
          });
          await platform.invokeMethod('requestDeviceAdmin');
          break;
        case 'batteryOptimization':
          await platform.invokeMethod('requestIgnoreBatteryOptimizations');
          break;
        case 'accessibility':
          await platform.invokeMethod('requestAccessibility');
          break;
        case 'overlay':
          await platform.invokeMethod('requestOverlay');
          break;
        case 'location':
          await platform.invokeMethod('requestLocation');
          break;
        case 'sms':
          await platform.invokeMethod('requestSms');
          break;
        case 'phone':
          await platform.invokeMethod('requestPhone');
          break;
        case 'notification':
          await platform.invokeMethod('requestNotification');
          break;
        default:
          print('RunningDPC: Unknown permission: $permissionKey');
          return;
      }

      if (permissionKey == 'deviceAdmin') {
        print('RunningDPC: 🔄 Waiting 10 seconds for device admin permission...');
        await Future.delayed(const Duration(seconds: 10));
        print('RunningDPC: ✅ Finished waiting for device admin permission');
        setState(() {
          _isProcessingDeviceAdmin = false;
        });
      } else {
        await Future.delayed(const Duration(seconds: 2));
      }

      await _checkPermissions();

      if (permissionKey == 'deviceAdmin') {
        print('RunningDPC: Device admin permission processed - explicitly preventing auto-navigation');
      }

      print('RunningDPC: Permission request completed for $permissionKey, staying on permission screen');
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error requesting $permissionKey permission: $e'),
            backgroundColor: FastEmiTheme.red,
          ),
        );
      }
      print('RunningDPC: Error requesting $permissionKey permission: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    if (AppConfig.usesFastEmiUi) {
      return _buildFastEmiUI(context);
    }

    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      appBar: AppBar(
        title: const Text(
          "Permission Setup",
          style: TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.bold,
            fontSize: 20,
          ),
        ),
        backgroundColor: const Color(0xFF2E3192),
        elevation: 0,
        centerTitle: true,
        systemOverlayStyle: SystemUiOverlayStyle.light,
      ),
      body: SafeArea(
        child: _isLoading
            ? const Center(
                child: CircularProgressIndicator(
                  color: Color(0xFF2E3192),
                ),
              )
            : Column(
                children: [
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(24),
                    decoration: const BoxDecoration(
                      color: Color(0xFF2E3192),
                    ),
                    child: Column(
                      children: [
                        const Icon(
                          Icons.security,
                          size: 60,
                          color: Colors.white,
                        ),
                        const SizedBox(height: 16),
                        const Text(
                          "Grant Permissions",
                          style: TextStyle(
                            fontSize: 24,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                          ),
                        ),
                        const SizedBox(height: 8),
                        const Text(
                          "All permissions are required for app to function properly",
                          style: TextStyle(
                            fontSize: 16,
                            color: Colors.white70,
                          ),
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),
                  ),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: ListView.builder(
                        itemCount: _permissions.length,
                        itemBuilder: (context, index) {
                          final permission = _permissions[index];
                          final isGranted = _permissionStatus[permission['key']] ?? false;

                          return Container(
                            margin: const EdgeInsets.only(bottom: 12),
                            decoration: BoxDecoration(
                              color: Colors.white,
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(
                                color: isGranted ? Colors.green : Colors.grey[300]!,
                                width: isGranted ? 2 : 1,
                              ),
                              boxShadow: [
                                BoxShadow(
                                  color: Colors.black.withOpacity(0.05),
                                  blurRadius: 10,
                                  offset: const Offset(0, 2),
                                ),
                              ],
                            ),
                            child: ListTile(
                              contentPadding: const EdgeInsets.all(16),
                              leading: Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: isGranted ? Colors.green.withOpacity(0.1) : Colors.grey[100],
                                  borderRadius: BorderRadius.circular(10),
                                ),
                                child: Icon(
                                  permission['icon'],
                                  color: isGranted ? Colors.green : Colors.grey[600],
                                  size: 24,
                                ),
                              ),
                              title: Text(
                                permission['name'],
                                style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                  color: isGranted ? Colors.green[800] : Colors.black87,
                                ),
                              ),
                              subtitle: Text(
                                permission['description'],
                                style: TextStyle(
                                  fontSize: 14,
                                  color: Colors.grey[600],
                                ),
                              ),
                              trailing: isGranted
                                  ? Container(
                                      padding: const EdgeInsets.all(8),
                                      decoration: BoxDecoration(
                                        color: Colors.green,
                                        borderRadius: BorderRadius.circular(20),
                                      ),
                                      child: const Icon(
                                        Icons.check,
                                        color: Colors.white,
                                        size: 20,
                                      ),
                                    )
                                  : Container(
                                      padding: const EdgeInsets.all(8),
                                      decoration: BoxDecoration(
                                        color: Colors.red,
                                        borderRadius: BorderRadius.circular(20),
                                      ),
                                      child: const Icon(
                                        Icons.close,
                                        color: Colors.white,
                                        size: 20,
                                      ),
                                    ),
                              onTap: isGranted ? null : () => _requestPermission(permission['key']),
                            ),
                          );
                        },
                      ),
                    ),
                  ),
                  Container(
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black.withOpacity(0.1),
                          blurRadius: 20,
                          offset: const Offset(0, -5),
                        ),
                      ],
                    ),
                    child: SizedBox(
                      width: double.infinity,
                      height: 56,
                      child: ElevatedButton(
                        onPressed: _allPermissionsGranted && !_isProcessingDeviceAdmin
                            ? () async {
                                print('RunningDPC: 🚀 User clicked Continue button - starting DPC services');
                                await widget.onAllPermissionsGranted();
                              }
                            : null,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: _allPermissionsGranted
                              ? const Color(0xFF2E3192)
                              : Colors.grey[300],
                          foregroundColor: _allPermissionsGranted
                              ? Colors.white
                              : Colors.grey[600],
                          elevation: _allPermissionsGranted ? 8 : 0,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(16),
                          ),
                        ),
                        child: Text(
                          _allPermissionsGranted ? 'Continue' : 'Grant All Permissions',
                          style: const TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  Widget _buildFastEmiUI(BuildContext context) {
    return Scaffold(
      backgroundColor: FastEmiTheme.surfaceGrey,
      body: _isLoading
          ? const Center(
              child: CircularProgressIndicator(
                color: FastEmiTheme.lime,
              ),
            )
          : FadeTransition(
              opacity: _fadeAnim,
              child: SlideTransition(
                position: _slideAnim,
                child: Column(
                  children: [
                    // 1. Dark Hero Header
                    Container(
                      width: double.infinity,
                      decoration: const BoxDecoration(
                        color: FastEmiTheme.bgDark,
                        borderRadius: BorderRadius.only(
                          bottomLeft: Radius.circular(32),
                          bottomRight: Radius.circular(32),
                        ),
                      ),
                      child: SafeArea(
                        bottom: false,
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(24, 24, 24, 32),
                          child: Column(
                            children: [
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: FastEmiTheme.lime.withOpacity(0.15),
                                  shape: BoxShape.circle,
                                  border: Border.all(
                                    color: FastEmiTheme.lime.withOpacity(0.3),
                                    width: 1.5,
                                  ),
                                ),
                                child: const Icon(
                                  Icons.security_rounded,
                                  size: 40,
                                  color: FastEmiTheme.lime,
                                ),
                              ),
                              const SizedBox(height: 16),
                              Text(
                                "Grant Permissions",
                                style: GoogleFonts.plusJakartaSans(
                                  fontSize: 24,
                                  fontWeight: FontWeight.w900,
                                  color: Colors.white,
                                ),
                              ),
                              const SizedBox(height: 8),
                              Text(
                                "All permissions are required for the application to function properly.",
                                style: GoogleFonts.plusJakartaSans(
                                  fontSize: 14,
                                  fontWeight: FontWeight.w500,
                                  color: Colors.white54,
                                ),
                                textAlign: TextAlign.center,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),

                    // 2. Section Label and Scrollable Permissions List
                    Padding(
                      padding: const EdgeInsets.fromLTRB(24, 20, 24, 8),
                      child: Row(
                        children: [
                          Text(
                            'REQUIRED PERMISSIONS',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 11,
                              fontWeight: FontWeight.w800,
                              color: FastEmiTheme.textLight,
                              letterSpacing: 1.8,
                            ),
                          ),
                        ],
                      ),
                    ),

                    Expanded(
                      child: ListView.builder(
                        physics: const BouncingScrollPhysics(),
                        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
                        itemCount: _permissions.length,
                        itemBuilder: (context, index) {
                          final permission = _permissions[index];
                          final isGranted = _permissionStatus[permission['key']] ?? false;

                          return Container(
                            margin: const EdgeInsets.only(bottom: 12),
                            decoration: BoxDecoration(
                              color: FastEmiTheme.cardBg,
                              borderRadius: BorderRadius.circular(16),
                              boxShadow: [
                                BoxShadow(
                                  color: FastEmiTheme.bgDark.withOpacity(0.04),
                                  blurRadius: 10,
                                  offset: const Offset(0, 3),
                                ),
                              ],
                              border: Border.all(
                                color: isGranted 
                                    ? FastEmiTheme.green.withOpacity(0.3)
                                    : FastEmiTheme.lime.withOpacity(0.08),
                                width: isGranted ? 1.5 : 1,
                              ),
                            ),
                            child: ListTile(
                              contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                              leading: Container(
                                padding: const EdgeInsets.all(10),
                                decoration: BoxDecoration(
                                  color: isGranted
                                      ? FastEmiTheme.green.withOpacity(0.1)
                                      : FastEmiTheme.surfaceGrey,
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Icon(
                                  permission['icon'],
                                  color: isGranted ? FastEmiTheme.green : FastEmiTheme.textMid,
                                  size: 24,
                                ),
                              ),
                              title: Text(
                                permission['name'],
                                style: GoogleFonts.plusJakartaSans(
                                  fontSize: 15,
                                  fontWeight: FontWeight.w800,
                                  color: FastEmiTheme.textDark,
                                ),
                              ),
                              subtitle: Text(
                                permission['description'],
                                style: GoogleFonts.plusJakartaSans(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w500,
                                  color: FastEmiTheme.textLight,
                                ),
                              ),
                              trailing: Container(
                                padding: const EdgeInsets.all(6),
                                decoration: BoxDecoration(
                                  color: isGranted ? FastEmiTheme.green : FastEmiTheme.red,
                                  shape: BoxShape.circle,
                                ),
                                child: Icon(
                                  isGranted ? Icons.check_rounded : Icons.close_rounded,
                                  color: Colors.white,
                                  size: 16,
                                ),
                              ),
                              onTap: isGranted ? null : () => _requestPermission(permission['key']),
                            ),
                          );
                        },
                      ),
                    ),

                    // 3. Continue CTA Button
                    Container(
                      padding: const EdgeInsets.all(24),
                      decoration: BoxDecoration(
                        color: FastEmiTheme.cardBg,
                        boxShadow: [
                          BoxShadow(
                            color: FastEmiTheme.bgDark.withOpacity(0.06),
                            blurRadius: 20,
                            offset: const Offset(0, -6),
                          ),
                        ],
                      ),
                      child: SafeArea(
                        top: false,
                        child: SizedBox(
                          width: double.infinity,
                          height: 54,
                          child: ElevatedButton(
                            onPressed: _allPermissionsGranted && !_isProcessingDeviceAdmin
                                ? () async {
                                    print('RunningDPC: 🚀 User clicked Continue button - starting DPC services');
                                    await widget.onAllPermissionsGranted();
                                  }
                                : null,
                            style: ElevatedButton.styleFrom(
                              backgroundColor: _allPermissionsGranted
                                  ? FastEmiTheme.lime
                                  : Colors.grey.shade300,
                              foregroundColor: _allPermissionsGranted
                                  ? FastEmiTheme.bgDark
                                  : Colors.grey.shade500,
                              elevation: _allPermissionsGranted ? 4 : 0,
                              shadowColor: FastEmiTheme.lime.withOpacity(0.3),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(18),
                              ),
                            ),
                            child: Text(
                              _allPermissionsGranted ? 'Continue' : 'Grant All Permissions',
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 15,
                                fontWeight: FontWeight.w800,
                                color: _allPermissionsGranted 
                                    ? FastEmiTheme.bgDark
                                    : Colors.grey.shade600,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}
