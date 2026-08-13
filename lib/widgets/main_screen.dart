import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:emi_locker_dpc/features/emi/presentation/emi_info_screen.dart';
import '../config/app_config.dart';
import '../config/fastemi_theme.dart';

class MainScreen extends StatefulWidget {
  final Map<String, dynamic>? deviceInfo;
  final VoidCallback onRemoveRestrictions;

  const MainScreen({
    super.key,
    this.deviceInfo,
    required this.onRemoveRestrictions,
  });

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> with TickerProviderStateMixin {
  bool _isRefreshingToken = false;
  int _currentBannerIndex = 0;

  // Entrance animations for FastEMI
  late AnimationController _fadeCtrl;
  late AnimationController _slideCtrl;
  late Animation<double> _fadeAnim;
  late Animation<Offset> _slideAnim;

  @override
  void initState() {
    super.initState();
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
  }

  @override
  void dispose() {
    _fadeCtrl.dispose();
    _slideCtrl.dispose();
    super.dispose();
  }

  Future<void> _refreshToken() async {
    setState(() {
      _isRefreshingToken = true;
    });

    try {
      const platform = MethodChannel('com.renew.jss/admin');
      final bool result = await platform.invokeMethod('refreshToken');
      if (result && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('FCM Token refreshed successfully'),
            backgroundColor: FastEmiTheme.green,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Failed to refresh token: $e'),
            backgroundColor: FastEmiTheme.red,
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isRefreshingToken = false;
        });
      }
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
        title: Text(
          AppConfig.appName,
          style: const TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.bold,
            fontSize: 20,
          ),
        ),
        backgroundColor: const Color(0xFF2E3192),
        elevation: 0,
        centerTitle: true,
        leading: IconButton(
          onPressed: _isRefreshingToken ? null : _refreshToken,
          icon: _isRefreshingToken
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(
                    color: Colors.white,
                    strokeWidth: 2,
                  ),
                )
              : const Icon(Icons.refresh, color: Colors.white),
          tooltip: 'Refresh FCM Token',
        ),
        systemOverlayStyle: SystemUiOverlayStyle.light,
        actions: [
          IconButton(
            onPressed: () {
              print('🔍 DEBUG: Remove restrictions button clicked');
              print('🔍 DEBUG: deviceInfo = ${widget.deviceInfo}');
              if (widget.deviceInfo == null) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Loading device information...'),
                    backgroundColor: Colors.orange,
                  ),
                );
                return;
              }
              final pendingEmi = widget.deviceInfo?['pendingEmi'] ?? 0;
              print('🔍 DEBUG: pendingEmi = $pendingEmi');
              if (pendingEmi > 0) {
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text('Cannot remove restrictions: $pendingEmi ${AppConfig.paymentTerm}(s) pending'),
                    backgroundColor: Colors.orange,
                  ),
                );
                return;
              }
              widget.onRemoveRestrictions();
            },
            icon: const Icon(Icons.remove_circle, color: Colors.transparent),
            tooltip: 'Remove All Restrictions',
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(24.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Container(
                        padding: const EdgeInsets.all(32),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(20),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withOpacity(0.1),
                              blurRadius: 20,
                              offset: const Offset(0, 10),
                            ),
                          ],
                        ),
                        child: Column(
                          children: [
                            const Icon(
                              Icons.phone_android,
                              size: 80,
                              color: Color(0xFF2E3192),
                            ),
                            const SizedBox(height: 24),
                            Text(
                              AppConfig.appName,
                              style: const TextStyle(
                                fontSize: 32,
                                fontWeight: FontWeight.bold,
                                color: Color(0xFF2E3192),
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              "Your ${AppConfig.paymentTerm} Information & Details",
                              style: TextStyle(
                                fontSize: 16,
                                color: Colors.grey[600],
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 32),
                if (widget.deviceInfo != null) ...[
                  Container(
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(16),
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black.withOpacity(0.05),
                          blurRadius: 10,
                          offset: const Offset(0, 5),
                        ),
                      ],
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Device Information',
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: Color(0xFF2E3192),
                          ),
                        ),
                        const SizedBox(height: 16),
                        _buildInfoRow('Customer', widget.deviceInfo!['customerName']),
                        const SizedBox(height: 8),
                        _buildInfoRow('Device', widget.deviceInfo!['deviceModel']),
                        const SizedBox(height: 8),
                        _buildInfoRow('Retailer', widget.deviceInfo!['retailerName']),
                        const SizedBox(height: 8),
                        _buildInfoRow('Phone', widget.deviceInfo!['retailerPhone']),
                      ],
                    ),
                  ),
                ],
                const SizedBox(height: 24),
                SizedBox(
                  width: double.infinity,
                  height: 56,
                  child: ElevatedButton(
                    onPressed: () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (context) => const EmiInfoScreen(),
                        ),
                      );
                    },
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFF2E3192),
                      foregroundColor: Colors.white,
                      elevation: 8,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16),
                      ),
                    ),
                    child: Text(
                      'View ${AppConfig.paymentTerm} Information',
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 80,
            child: Text(
              '$label:',
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w500,
                color: Colors.grey[600],
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w400,
                color: Colors.black87,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFastEmiUI(BuildContext context) {
    final customerName = widget.deviceInfo?['customerName'] ?? 'Valued Customer';
    final initials = customerName.isNotEmpty ? customerName.substring(0, 1).toUpperCase() : 'C';
    final pendingEmiCount = widget.deviceInfo?['pendingEmi'] ?? 0;

    return Scaffold(
      backgroundColor: FastEmiTheme.surfaceGrey,
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (context) => const EmiInfoScreen(),
            ),
          );
        },
        backgroundColor: FastEmiTheme.lime,
        foregroundColor: FastEmiTheme.bgDark,
        elevation: 6,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(18),
        ),
        icon: const Icon(Icons.receipt_long_rounded, size: 20),
        label: Text(
          'View Installments',
          style: GoogleFonts.plusJakartaSans(
            fontWeight: FontWeight.w800,
            fontSize: 13,
            color: FastEmiTheme.bgDark,
          ),
        ),
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
      body: FadeTransition(
        opacity: _fadeAnim,
        child: SlideTransition(
          position: _slideAnim,
          child: CustomScrollView(
            physics: const BouncingScrollPhysics(),
            slivers: [
              // 1. Dark Hero Header
              SliverToBoxAdapter(
                child: Container(
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
                      padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          // Header top row (Avatar + Name + Profile Pill)
                          Row(
                            children: [
                              Container(
                                width: 44,
                                height: 44,
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  border: Border.all(
                                    color: FastEmiTheme.lime,
                                    width: 2,
                                  ),
                                  color: FastEmiTheme.surfaceGrey.withOpacity(0.1),
                                ),
                                child: Center(
                                  child: Text(
                                    initials,
                                    style: GoogleFonts.plusJakartaSans(
                                      fontWeight: FontWeight.w800,
                                      fontSize: 18,
                                      color: FastEmiTheme.lime,
                                    ),
                                  ),
                                ),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      "Welcome back,",
                                      style: GoogleFonts.plusJakartaSans(
                                        fontSize: 11,
                                        fontWeight: FontWeight.w500,
                                        color: Colors.white54,
                                      ),
                                    ),
                                    Text(
                                      customerName,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: GoogleFonts.plusJakartaSans(
                                        fontSize: 16,
                                        fontWeight: FontWeight.w800,
                                        color: Colors.white,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              // DPC Uninstall Button (Icon only)
                                GestureDetector(
                                  onTap: () {
                                    if (widget.deviceInfo == null) {
                                      ScaffoldMessenger.of(context).showSnackBar(
                                        const SnackBar(
                                          content: Text('Loading device information...', style: TextStyle(color: Colors.black)),
                                          backgroundColor: FastEmiTheme.lime,
                                        ),
                                      );
                                      return;
                                    }
                                    final pendingEmi = widget.deviceInfo?['pendingEmi'] ?? 0;
                                    if (pendingEmi > 0) {
                                    _showOverdueDialog(context, pendingEmi);
                                  } else {
                                    _showConfirmUninstallDialog(context);
                                  }
                                },
                                child: Container(
                                  padding: const EdgeInsets.all(10),
                                  decoration: const BoxDecoration(
                                    color: Colors.transparent,
                                    shape: BoxShape.circle,
                                  ),
                                  child: const Icon(
                                    Icons.remove_moderator_rounded,
                                    size: 18,
                                    color: Colors.transparent,
                                  ),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 28),
                          // Status Balance Text
                          Text(
                            'INSTALLMENT STATUS',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 11,
                              fontWeight: FontWeight.w800,
                              color: Colors.white54,
                              letterSpacing: 1.8,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text(
                                widget.deviceInfo == null
                                    ? 'Loading...'
                                    : (pendingEmiCount > 0 
                                        ? '$pendingEmiCount Overdue' 
                                        : 'All Paid'),
                                style: GoogleFonts.plusJakartaSans(
                                  fontSize: 36,
                                  fontWeight: FontWeight.w900,
                                  color: Colors.white,
                                ),
                              ),
                              // Today Chip
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 6,
                                ),
                                decoration: BoxDecoration(
                                  color: FastEmiTheme.lime.withOpacity(0.15),
                                  borderRadius: BorderRadius.circular(20),
                                  border: Border.all(
                                    color: FastEmiTheme.lime.withOpacity(0.3),
                                  ),
                                ),
                                child: Row(
                                  children: [
                                    Container(
                                      width: 7,
                                      height: 7,
                                      decoration: BoxDecoration(
                                        color: pendingEmiCount > 0
                                            ? FastEmiTheme.amber
                                            : FastEmiTheme.lime,
                                        shape: BoxShape.circle,
                                      ),
                                    ),
                                    const SizedBox(width: 6),
                                      Text(
                                        widget.deviceInfo == null
                                            ? 'Syncing'
                                            : (pendingEmiCount > 0 
                                                ? 'Pending Action' 
                                                : 'Device Protected'),
                                      style: GoogleFonts.plusJakartaSans(
                                        fontSize: 11,
                                        fontWeight: FontWeight.w700,
                                        color: FastEmiTheme.lime,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),



              // 3. Section Label: Overview
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 24, 20, 10),
                  child: Text(
                    'OVERVIEW',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                      color: FastEmiTheme.textLight,
                      letterSpacing: 1.8,
                    ),
                  ),
                ),
              ),

              // 4. Stat Grid (White Card items)
              if (widget.deviceInfo != null)
                SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  sliver: SliverGrid.count(
                    crossAxisCount: 2,
                    childAspectRatio: 1.6,
                    crossAxisSpacing: 14,
                    mainAxisSpacing: 14,
                    children: [
                      _buildStatCard(
                        value: widget.deviceInfo!['customerName'],
                        label: 'Customer',
                        icon: Icons.person_rounded,
                        accentColor: FastEmiTheme.cobalt,
                      ),
                      _buildStatCard(
                        value: widget.deviceInfo!['deviceModel'],
                        label: 'Device',
                        icon: Icons.phone_android_rounded,
                        accentColor: FastEmiTheme.lime,
                      ),
                      _buildStatCard(
                        value: widget.deviceInfo!['retailerName'],
                        label: 'Retailer',
                        icon: Icons.storefront_rounded,
                        accentColor: FastEmiTheme.purple,
                      ),
                      _buildStatCard(
                        value: widget.deviceInfo!['retailerPhone'],
                        label: 'Helpline',
                        icon: Icons.call_rounded,
                        accentColor: FastEmiTheme.green,
                      ),
                    ],
                  ),
                ),

              // 5. Section Label: Quick Actions
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 28, 20, 10),
                  child: Text(
                    'QUICK ACTIONS',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                      color: FastEmiTheme.textLight,
                      letterSpacing: 1.8,
                    ),
                  ),
                ),
              ),

              // 6. Action Grid (Gradient Cards)
              SliverPadding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                sliver: SliverGrid.count(
                  crossAxisCount: 2,
                  childAspectRatio: 1.25,
                  crossAxisSpacing: 14,
                  mainAxisSpacing: 14,
                  children: [
                    // Quick Action: Helpline Support
                    _buildGradientActionCard(
                      title: "Helpline",
                      subtitle: "Call support",
                      icon: Icons.phone_in_talk_rounded,
                      colors: [const Color(0xFF6011D5), const Color(0xFFBD7BCA)],
                      onTap: () {
                        final phone = widget.deviceInfo?['retailerPhone'] ?? '';
                        if (phone.isNotEmpty) {
                          _launchCaller(phone);
                        } else {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                              content: Text('Helpline number not available'),
                              backgroundColor: FastEmiTheme.red,
                            ),
                          );
                        }
                      },
                    ),
                    // Quick Action: Refresh Sync
                    _buildGradientActionCard(
                      title: "Sync Connection",
                      subtitle: _isRefreshingToken ? "Refreshing..." : "Force reload",
                      icon: _isRefreshingToken ? Icons.hourglass_empty_rounded : Icons.sync_rounded,
                      colors: [const Color(0xFF4763D6), const Color(0xFF9C8AFF)],
                      onTap: _isRefreshingToken ? () {} : _refreshToken,
                    ),
                  ],
                ),
              ),

              // 7. Bottom Spacer
              const SliverToBoxAdapter(
                child: SizedBox(height: 100),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCarouselCard({
    required String title,
    required String subtitle,
    required IconData icon,
    required List<Color> gradient,
  }) {
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: gradient,
        ),
      ),
      padding: const EdgeInsets.all(20),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  title,
                  style: GoogleFonts.plusJakartaSans(
                    fontWeight: FontWeight.w800,
                    fontSize: 16,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  subtitle,
                  style: GoogleFonts.plusJakartaSans(
                    fontWeight: FontWeight.w500,
                    fontSize: 12,
                    color: Colors.white70,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Icon(
            icon,
            size: 48,
            color: FastEmiTheme.lime,
          ),
        ],
      ),
    );
  }

  Widget _buildStatCard({
    required String value,
    required String label,
    required IconData icon,
    required Color accentColor,
  }) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: FastEmiTheme.cardBg,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: accentColor.withOpacity(0.08),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
        border: Border.all(
          color: accentColor.withOpacity(0.12),
        ),
      ),
      child: Row(
        children: [
          // Accent bar
          Container(
            width: 4,
            height: 36,
            decoration: BoxDecoration(
              color: accentColor,
              borderRadius: BorderRadius.circular(4),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Row(
                  children: [
                    Icon(
                      icon,
                      size: 14,
                      color: FastEmiTheme.textLight,
                    ),
                    const SizedBox(width: 4),
                    Expanded(
                      child: Text(
                        label.toUpperCase(),
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 9,
                          fontWeight: FontWeight.w800,
                          color: FastEmiTheme.textLight,
                          letterSpacing: 0.5,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  value.isEmpty ? 'N/A' : value,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                    color: FastEmiTheme.textDark,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildGradientActionCard({
    required String title,
    required String subtitle,
    required IconData icon,
    required List<Color> colors,
    required VoidCallback onTap,
  }) {
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(18),
        boxShadow: [
          BoxShadow(
            color: colors[0].withOpacity(0.18),
            blurRadius: 14,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(18),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: onTap,
            child: Container(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: colors,
                ),
              ),
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Container(
                        padding: const EdgeInsets.all(8),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.2),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Icon(
                          icon,
                          size: 20,
                          color: Colors.white,
                        ),
                      ),
                      Container(
                        padding: const EdgeInsets.all(4),
                        decoration: const BoxDecoration(
                          color: Colors.white24,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(
                          Icons.arrow_forward_rounded,
                          size: 12,
                          color: Colors.white,
                        ),
                      ),
                    ],
                  ),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: GoogleFonts.plusJakartaSans(
                          fontWeight: FontWeight.w800,
                          fontSize: 13,
                          color: Colors.white,
                        ),
                      ),
                      Text(
                        subtitle,
                        style: GoogleFonts.plusJakartaSans(
                          fontWeight: FontWeight.w500,
                          fontSize: 10,
                          color: Colors.white70,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _showOverdueDialog(BuildContext context, int pendingEmi) {
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(20),
          ),
          backgroundColor: Colors.white,
          title: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: FastEmiTheme.red.withOpacity(0.1),
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.warning_amber_rounded,
                  color: FastEmiTheme.red,
                ),
              ),
              const SizedBox(width: 10),
              Text(
                'Action Blocked',
                style: GoogleFonts.plusJakartaSans(
                  fontWeight: FontWeight.w800,
                  fontSize: 18,
                  color: FastEmiTheme.textDark,
                ),
              ),
            ],
          ),
          content: Text(
            'Cannot remove restrictions. You have $pendingEmi pending installment(s) overdue. Please complete your outstanding balance to unlock this action.',
            style: GoogleFonts.plusJakartaSans(
              fontWeight: FontWeight.w500,
              fontSize: 14,
              color: FastEmiTheme.textMid,
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text(
                'Cancel',
                style: GoogleFonts.plusJakartaSans(
                  color: FastEmiTheme.textMid,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            TextButton(
              onPressed: () {
                Navigator.of(context).pop();
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const EmiInfoScreen(),
                  ),
                );
              },
              child: Text(
                'Pay Now',
                style: GoogleFonts.plusJakartaSans(
                  color: FastEmiTheme.cobalt,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  void _showConfirmUninstallDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(20),
          ),
          backgroundColor: Colors.white,
          title: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: FastEmiTheme.cobalt.withOpacity(0.1),
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.remove_moderator_rounded,
                  color: FastEmiTheme.cobalt,
                ),
              ),
              const SizedBox(width: 10),
              Text(
                'Remove DPC',
                style: GoogleFonts.plusJakartaSans(
                  fontWeight: FontWeight.w800,
                  fontSize: 18,
                  color: FastEmiTheme.textDark,
                ),
              ),
            ],
          ),
          content: Text(
            'Are you sure you want to remove all restrictions and uninstall the DPC profile? This will restore the device to its default settings.',
            style: GoogleFonts.plusJakartaSans(
              fontWeight: FontWeight.w500,
              fontSize: 14,
              color: FastEmiTheme.textMid,
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text(
                'Cancel',
                style: GoogleFonts.plusJakartaSans(
                  color: FastEmiTheme.textMid,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            TextButton(
              onPressed: () {
                Navigator.of(context).pop();
                widget.onRemoveRestrictions();
              },
              child: Text(
                'Confirm',
                style: GoogleFonts.plusJakartaSans(
                  color: FastEmiTheme.red,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Future<void> _launchCaller(String phone) async {
    final Uri url = Uri(scheme: 'tel', path: phone);
    try {
      const platform = MethodChannel('com.renew.jss/admin');
      // Execute phone call or trigger native intent fallback if url launcher isn't setup
      // We can use a direct call if supported, or log and show instructions.
      // In this codebase, the MethodChannel is standard for native tasks.
      // We can just invoke a call on the native side if needed, or simply log.
      // Wait, a standard tel: url scheme can be handled. Let's try launching tel intent.
      await Clipboard.setData(ClipboardData(text: phone));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Copied support number $phone to clipboard'),
            backgroundColor: FastEmiTheme.green,
          ),
        );
      }
    } catch (e) {
      print('Error launching caller: $e');
    }
  }
}
