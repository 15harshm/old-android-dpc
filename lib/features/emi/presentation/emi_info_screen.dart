import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../../services/policy_api_service.dart';
import '../../../core/device_info_response.dart';
import '../../../utils/network_helper.dart';
import '../../../config/app_config.dart';
import '../../../config/fastemi_theme.dart';

class EmiInfoScreen extends StatefulWidget {
  const EmiInfoScreen({super.key});

  @override
  State<EmiInfoScreen> createState() => _EmiInfoScreenState();
}

class _EmiInfoScreenState extends State<EmiInfoScreen> with TickerProviderStateMixin {
  DeviceInfoResponse? deviceInfo;
  bool isLoading = true;
  String? errorMessage;

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

    _fetchDeviceInfo();
  }

  @override
  void dispose() {
    _fadeCtrl.dispose();
    _slideCtrl.dispose();
    super.dispose();
  }

  Future<void> _fetchDeviceInfo() async {
    setState(() {
      isLoading = true;
      errorMessage = null;
    });
    
    try {
      NetworkHelper.logNetworkStatus('fetchDeviceInfoWithDeviceImei');
      final hasNetwork = await NetworkHelper.isNetworkAvailable();
      
      if (!hasNetwork) {
        setState(() {
          isLoading = false;
          errorMessage = 'No internet connection. Please check your network and try again.';
        });
        print('⚠️ No network - skipping EMI info fetch');
        return;
      }
      
      final info = await PolicyApiService.fetchDeviceInfoWithDeviceImei(
        imei1: await _getSavedImei1(),
        imei2: await _getSavedImei2(),
      );
      setState(() {
        deviceInfo = info;
        isLoading = false;
        if (info == null) {
          errorMessage = 'Unable to fetch device information. Please try again later.';
        }
      });
    } catch (e) {
      setState(() {
        isLoading = false;
        errorMessage = 'Network error: Unable to load device information. Please check your connection and try again.';
      });
      print('❌ EMI info fetch error: $e');
    }
  }
  
  Future<String?> _getSavedImei1() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString('user_imei1');
    } catch (e) {
      print('Error getting saved IMEI1: $e');
      return null;
    }
  }
  
  Future<String?> _getSavedImei2() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString('user_imei2');
    } catch (e) {
      print('Error getting saved IMEI2: $e');
      return null;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (AppConfig.usesFastEmiUi) {
      return _buildFastEmiUI(context);
    }

    return Scaffold(
      backgroundColor: Colors.grey[50],
      appBar: AppBar(
        title: Text(
          '${AppConfig.paymentTerm} Information',
          style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.white),
        ),
        backgroundColor: const Color(0xFF2E3192),
        elevation: 0,
        systemOverlayStyle: SystemUiOverlayStyle.light,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh, color: Colors.white),
            onPressed: _fetchDeviceInfo,
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _fetchDeviceInfo,
        child: _buildBody(),
      ),
    );
  }

  Widget _buildBody() {
    if (isLoading) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const CircularProgressIndicator(color: Color(0xFF2E3192)),
            const SizedBox(height: 16),
            Text(
              'Loading ${AppConfig.paymentTerm} Information...',
              style: const TextStyle(fontSize: 16, color: Colors.grey),
            ),
          ],
        ),
      );
    }

    if (errorMessage != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.error_outline, size: 64, color: Colors.red[400]),
              const SizedBox(height: 16),
              Text(
                'Error',
                style: TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.bold,
                  color: Colors.red[400],
                ),
              ),
              const SizedBox(height: 8),
              Text(
                errorMessage!,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 16, color: Colors.grey),
              ),
              const SizedBox(height: 24),
              ElevatedButton.icon(
                onPressed: _fetchDeviceInfo,
                icon: const Icon(Icons.refresh),
                label: const Text('Retry'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF2E3192),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                ),
              ),
            ],
          ),
        ),
      );
    }

    if (deviceInfo == null) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.phone_android, size: 64, color: Colors.grey[400]),
            const SizedBox(height: 16),
            Text(
              'No Device Information',
              style: TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
                color: Colors.grey[600],
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Unable to retrieve ${AppConfig.paymentTerm} details',
              style: const TextStyle(fontSize: 16, color: Colors.grey),
            ),
          ],
        ),
      );
    }

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildCustomerInfoCard(),
          const SizedBox(height: 16),
          _buildDeviceInfoCard(),
          const SizedBox(height: 16),
          _buildEmiSummaryCard(),
          const SizedBox(height: 16),
          _buildEmiListCard(),
        ],
      ),
    );
  }

  Widget _buildCustomerInfoCard() {
    final customer = deviceInfo!.customer;
    final user = deviceInfo!.user;

    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: const Color(0xFF2E3192).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(
                    Icons.person,
                    color: Color(0xFF2E3192),
                    size: 24,
                  ),
                ),
                const SizedBox(width: 12),
                const Text(
                  'Customer Information',
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFF2E3192),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            _buildInfoRow('Customer Name', customer.customerName),
            _buildInfoRow('Phone Number', customer.phoneNumber),
            _buildInfoRow('Address', user.address),
            _buildInfoRow('User Name', user.userName),
            _buildInfoRow('User Phone', user.phone),
          ],
        ),
      ),
    );
  }

  Widget _buildDeviceInfoCard() {
    final customer = deviceInfo!.customer;

    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: const Color(0xFF2E3192).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(
                    Icons.phone_android,
                    color: Color(0xFF2E3192),
                    size: 24,
                  ),
                ),
                const SizedBox(width: 12),
                const Text(
                  'Device Information',
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFF2E3192),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            _buildInfoRow('Brand', customer.brand),
            _buildInfoRow('Model', customer.model),
            _buildInfoRow('IMEI 1', customer.imei1),
            if (customer.imei2.isNotEmpty) _buildInfoRow('IMEI 2', customer.imei2),
            _buildInfoRow('Device Price', '${AppConfig.currencySymbol}${(double.tryParse(customer.devicePrice) ?? 0.0).toStringAsFixed(2)}'),
            _buildInfoRow('Processing Fee', '${AppConfig.currencySymbol}${(double.tryParse(customer.processingFee) ?? 0.0).toStringAsFixed(2)}'),
            _buildInfoRow('Down Payment', '${AppConfig.currencySymbol}${(double.tryParse(customer.downPayment) ?? 0.0).toStringAsFixed(2)}'),
            _buildInfoRow('Monthly ${AppConfig.paymentTerm}', '${AppConfig.currencySymbol}${(double.tryParse(customer.monthlyEmi) ?? 0.0).toStringAsFixed(2)}'),
            _buildInfoRow('Interest Rate', '${customer.interestRate}%'),
            _buildInfoRow('Number of ${AppConfig.paymentTerm}s', customer.numberOfEmi),
          ],
        ),
      ),
    );
  }

  Widget _buildEmiSummaryCard() {
    final summary = deviceInfo!.emiSummary;

    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: const Color(0xFF2E3192).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(
                    Icons.account_balance_wallet,
                    color: Color(0xFF2E3192),
                    size: 24,
                  ),
                ),
                const SizedBox(width: 12),
                Text(
                  '${AppConfig.paymentTerm} Summary',
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFF2E3192),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: _buildSummaryItem(
                    'Total ${AppConfig.paymentTerm}',
                    '${(summary.totalEmi)}',
                    Colors.blue,
                  ),
                ),
                const SizedBox(width: 4),
                Expanded(
                  child: _buildSummaryItem(
                    'Paid ${AppConfig.paymentTerm}',
                    '${summary.paidEmi ?? 0}',
                    Colors.green,
                  ),
                ),
                const SizedBox(width: 4),
                Expanded(
                  child: _buildSummaryItem(
                    'Pending ${AppConfig.paymentTerm}',
                    '${summary.pendingEmi ?? 0}',
                    Colors.orange,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF2E3192).withOpacity(0.1),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Total Amount',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                      color: Color(0xFF2E3192),
                    ),
                  ),
                  Text(
                    '${AppConfig.currencySymbol}${summary.totalAmount.toStringAsFixed(2)}',
                    style: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                      color: Color(0xFF2E3192),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSummaryItem(String label, String value, Color color) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withOpacity(0.1),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withOpacity(0.3)),
      ),
      child: Column(
        children: [
          Text(
            label,
            style: TextStyle(
              fontSize: 12,
              color: color.withOpacity(0.8),
              fontWeight: FontWeight.w500,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.bold,
              color: color,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEmiListCard() {
    final emiList = deviceInfo!.emiList;

    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: const Color(0xFF2E3192).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(
                    Icons.list_alt,
                    color: Color(0xFF2E3192),
                    size: 24,
                  ),
                ),
                const SizedBox(width: 12),
                Text(
                  '${AppConfig.paymentTerm} Schedule',
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFF2E3192),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            ListView.separated(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              itemCount: emiList.length,
              separatorBuilder: (context, index) => const Divider(height: 1),
              itemBuilder: (context, index) {
                final emi = emiList[index];
                return _buildEmiTile(emi);
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmiTile(EmiDetail emi) {
    Color statusColor;
    String statusText;
    IconData statusIcon;
    bool hasPenalty = false;
    double penaltyAmount = 0.0;

    if (emi.isPaid) {
      statusColor = Colors.green;
      statusText = 'Paid';
      statusIcon = Icons.check_circle;
    } else if (emi.isOverdue()) {
      statusColor = Colors.red;
      statusText = 'Overdue';
      statusIcon = Icons.warning;
      if (deviceInfo != null && deviceInfo!.penalty > 0) {
        hasPenalty = true;
        penaltyAmount = deviceInfo!.penalty;
      }
    } else {
      statusColor = Colors.orange;
      statusText = 'Pending';
      statusIcon = Icons.schedule;
    }

    final double baseAmount = double.tryParse(emi.amount) ?? 0.0;
    final double totalEmiAmount = baseAmount + penaltyAmount;

    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(
        backgroundColor: statusColor.withOpacity(0.1),
        child: Text(
          '${emi.emiNo}',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: statusColor,
          ),
        ),
      ),
      title: Text(
        '${AppConfig.paymentTerm} ${emi.emiNo}',
        style: const TextStyle(fontWeight: FontWeight.bold),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Due: ${_formatDate(emi.dueDate)}',
            style: TextStyle(color: Colors.grey[600]),
          ),
          if (hasPenalty)
            Container(
              margin: const EdgeInsets.only(top: 4),
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: Colors.red[50],
                borderRadius: BorderRadius.circular(4),
                border: Border.all(color: Colors.red[100]!),
              ),
              child: Text(
                'Penalty: ${AppConfig.currencySymbol}${penaltyAmount.toStringAsFixed(2)} included',
                style: TextStyle(
                  color: Colors.red[700],
                  fontSize: 11,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
        ],
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                '${AppConfig.currencySymbol}${totalEmiAmount.toStringAsFixed(2)}',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 16,
                  color: hasPenalty ? Colors.red[700] : Colors.black,
                ),
              ),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    statusIcon,
                    size: 16,
                    color: statusColor,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    statusText,
                    style: TextStyle(
                      color: statusColor,
                      fontWeight: FontWeight.w500,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ],
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
            width: 120,
            child: Text(
              '$label:',
              style: TextStyle(
                fontWeight: FontWeight.w500,
                color: Colors.grey[600],
                fontSize: 14,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value.isEmpty ? 'N/A' : value,
              style: const TextStyle(
                fontWeight: FontWeight.w500,
                fontSize: 14,
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _formatDate(String dateString) {
    try {
      final date = DateTime.parse(dateString);
      return '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
    } catch (e) {
      return dateString;
    }
  }

  // ==================== FastEMI Custom UI ====================
  Widget _buildFastEmiUI(BuildContext context) {
    return Scaffold(
      backgroundColor: FastEmiTheme.surfaceGrey,
      body: RefreshIndicator(
        color: FastEmiTheme.lime,
        backgroundColor: FastEmiTheme.bgDark,
        onRefresh: _fetchDeviceInfo,
        child: FadeTransition(
          opacity: _fadeAnim,
          child: SlideTransition(
            position: _slideAnim,
            child: _buildFastEmiBody(),
          ),
        ),
      ),
    );
  }

  Widget _buildFastEmiBody() {
    if (isLoading) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const CircularProgressIndicator(color: FastEmiTheme.lime),
            const SizedBox(height: 16),
            Text(
              'Loading installment information...',
              style: GoogleFonts.plusJakartaSans(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: FastEmiTheme.textMid,
              ),
            ),
          ],
        ),
      );
    }

    if (errorMessage != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.error_outline_rounded, size: 64, color: Colors.red[400]),
              const SizedBox(height: 16),
              Text(
                'Sync Error',
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 20,
                  fontWeight: FontWeight.w800,
                  color: FastEmiTheme.red,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                errorMessage!,
                textAlign: TextAlign.center,
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                  color: FastEmiTheme.textMid,
                ),
              ),
              const SizedBox(height: 24),
              ElevatedButton.icon(
                onPressed: _fetchDeviceInfo,
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('Retry Connection'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: FastEmiTheme.bgDark,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    }

    if (deviceInfo == null) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.phone_android_rounded, size: 64, color: FastEmiTheme.textLight),
            const SizedBox(height: 16),
            Text(
              'No installment information',
              style: GoogleFonts.plusJakartaSans(
                fontSize: 20,
                fontWeight: FontWeight.w800,
                color: FastEmiTheme.textMid,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Unable to retrieve installment details.',
              style: GoogleFonts.plusJakartaSans(
                fontSize: 14,
                color: FastEmiTheme.textLight,
              ),
            ),
          ],
        ),
      );
    }

    final summary = deviceInfo!.emiSummary;

    return CustomScrollView(
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
                padding: const EdgeInsets.fromLTRB(12, 16, 20, 32),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Title row with Back & Refresh Buttons
                    Row(
                      children: [
                        IconButton(
                          icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Colors.white, size: 20),
                          onPressed: () => Navigator.of(context).pop(),
                        ),
                        const SizedBox(width: 4),
                        Text(
                          'Installment Details',
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 20,
                            fontWeight: FontWeight.w900,
                            color: Colors.white,
                          ),
                        ),
                        const Spacer(),
                        IconButton(
                          icon: const Icon(Icons.refresh_rounded, color: Colors.white),
                          onPressed: _fetchDeviceInfo,
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),
                    Padding(
                      padding: const EdgeInsets.only(left: 12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'PENDING INSTALLMENTS',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 11,
                              fontWeight: FontWeight.w800,
                              color: Colors.white54,
                              letterSpacing: 1.8,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            '${AppConfig.currencySymbol}${summary.totalAmount.toStringAsFixed(2)}',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 36,
                              fontWeight: FontWeight.w900,
                              color: Colors.white,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),

        // 2. Section Label: Overview
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

        // 3. Stats Overview white cards row
        SliverPadding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          sliver: SliverToBoxAdapter(
            child: Row(
              children: [
                Expanded(
                  child: _buildFastEmiSummaryItem(
                    'Total',
                    '${summary.totalEmi}',
                    FastEmiTheme.cobalt,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: _buildFastEmiSummaryItem(
                    'Paid',
                    '${summary.paidEmi ?? 0}',
                    FastEmiTheme.green,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: _buildFastEmiSummaryItem(
                    'Pending',
                    '${summary.pendingEmi ?? 0}',
                    FastEmiTheme.amber,
                  ),
                ),
              ],
            ),
          ),
        ),

        // 4. Customer & Device Details Cards
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
          sliver: SliverToBoxAdapter(
            child: Column(
              children: [
                _buildFastEmiDetailsCard(
                  title: "Customer Details",
                  icon: Icons.person_rounded,
                  accentColor: FastEmiTheme.cobalt,
                  rows: [
                    _buildDetailRow("Customer Name", deviceInfo!.customer.customerName),
                    _buildDetailRow("Phone Number", deviceInfo!.customer.phoneNumber),
                    _buildDetailRow("Address", deviceInfo!.user.address),
                    _buildDetailRow("Retailer Name", deviceInfo!.user.userName),
                  ],
                ),
                const SizedBox(height: 16),
                _buildFastEmiDetailsCard(
                  title: "Device Details",
                  icon: Icons.phone_android_rounded,
                  accentColor: FastEmiTheme.lime,
                  rows: [
                    _buildDetailRow("Brand", deviceInfo!.customer.brand),
                    _buildDetailRow("Model", deviceInfo!.customer.model),
                    _buildDetailRow("IMEI 1", deviceInfo!.customer.imei1),
                    _buildDetailRow("Down Payment", "${AppConfig.currencySymbol}${(double.tryParse(deviceInfo!.customer.downPayment) ?? 0.0).toStringAsFixed(2)}"),
                    _buildDetailRow("Monthly Charge", "${AppConfig.currencySymbol}${(double.tryParse(deviceInfo!.customer.monthlyEmi) ?? 0.0).toStringAsFixed(2)}"),
                  ],
                ),
              ],
            ),
          ),
        ),

        // 5. Section Label: Schedule
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 28, 20, 10),
            child: Text(
              'INSTALLMENT SCHEDULE',
              style: GoogleFonts.plusJakartaSans(
                fontSize: 11,
                fontWeight: FontWeight.w800,
                color: FastEmiTheme.textLight,
                letterSpacing: 1.8,
              ),
            ),
          ),
        ),

        // 6. Installments List
        SliverPadding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          sliver: SliverList(
            delegate: SliverChildBuilderDelegate(
              (context, index) {
                final emi = deviceInfo!.emiList[index];
                return _buildFastEmiTile(emi);
              },
              childCount: deviceInfo!.emiList.length,
            ),
          ),
        ),

        // Bottom spacer
        const SliverToBoxAdapter(
          child: SizedBox(height: 80),
        ),
      ],
    );
  }

  Widget _buildFastEmiSummaryItem(String label, String value, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 14),
      decoration: BoxDecoration(
        color: FastEmiTheme.cardBg,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: color.withOpacity(0.08),
            blurRadius: 12,
            offset: const Offset(0, 4),
          ),
        ],
        border: Border.all(color: color.withOpacity(0.12)),
      ),
      child: Column(
        children: [
          Text(
            label.toUpperCase(),
            style: GoogleFonts.plusJakartaSans(
              fontSize: 10,
              color: FastEmiTheme.textLight,
              fontWeight: FontWeight.w800,
              letterSpacing: 0.5,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            value,
            style: GoogleFonts.plusJakartaSans(
              fontSize: 20,
              fontWeight: FontWeight.w900,
              color: color,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFastEmiDetailsCard({
    required String title,
    required IconData icon,
    required Color accentColor,
    required List<Widget> rows,
  }) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: FastEmiTheme.cardBg,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: FastEmiTheme.bgDark.withOpacity(0.04),
            blurRadius: 16,
            offset: const Offset(0, 6),
          ),
        ],
        border: Border.all(color: FastEmiTheme.lime.withOpacity(0.08)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: accentColor.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  icon,
                  color: accentColor,
                  size: 20,
                ),
              ),
              const SizedBox(width: 12),
              Text(
                title,
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                  color: FastEmiTheme.textDark,
                ),
              ),
            ],
          ),
          const Divider(height: 24, thickness: 1, color: FastEmiTheme.surfaceGrey),
          ...rows,
        ],
      ),
    );
  }

  Widget _buildDetailRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 4,
            child: Text(
              label,
              style: GoogleFonts.plusJakartaSans(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: FastEmiTheme.textLight,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 6,
            child: Text(
              value.isEmpty ? 'N/A' : value,
              style: GoogleFonts.plusJakartaSans(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: FastEmiTheme.textDark,
              ),
              textAlign: TextAlign.end,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFastEmiTile(EmiDetail emi) {
    Color statusColor;
    String statusText;
    IconData statusIcon;
    bool hasPenalty = false;
    double penaltyAmount = 0.0;

    if (emi.isPaid) {
      statusColor = FastEmiTheme.green;
      statusText = 'Paid';
      statusIcon = Icons.check_circle_rounded;
    } else if (emi.isOverdue()) {
      statusColor = FastEmiTheme.red;
      statusText = 'Overdue';
      statusIcon = Icons.error_rounded;
      if (deviceInfo != null && deviceInfo!.penalty > 0) {
        hasPenalty = true;
        penaltyAmount = deviceInfo!.penalty;
      }
    } else {
      statusColor = FastEmiTheme.amber;
      statusText = 'Pending';
      statusIcon = Icons.schedule_rounded;
    }

    final double baseAmount = double.tryParse(emi.amount) ?? 0.0;
    final double totalEmiAmount = baseAmount + penaltyAmount;

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: FastEmiTheme.cardBg,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: FastEmiTheme.bgDark.withOpacity(0.03),
            blurRadius: 10,
            offset: const Offset(0, 3),
          ),
        ],
        border: Border.all(
          color: statusColor.withOpacity(0.12),
        ),
      ),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        leading: CircleAvatar(
          backgroundColor: statusColor.withOpacity(0.1),
          child: Text(
            '${emi.emiNo}',
            style: GoogleFonts.plusJakartaSans(
              fontWeight: FontWeight.w800,
              color: statusColor,
            ),
          ),
        ),
        title: Text(
          'Installment ${emi.emiNo}',
          style: GoogleFonts.plusJakartaSans(
            fontWeight: FontWeight.w800,
            color: FastEmiTheme.textDark,
          ),
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 4),
            Text(
              'Due: ${_formatDate(emi.dueDate)}',
              style: GoogleFonts.plusJakartaSans(
                fontSize: 12,
                fontWeight: FontWeight.w500,
                color: FastEmiTheme.textMid,
              ),
            ),
            if (hasPenalty)
              Container(
                margin: const EdgeInsets.only(top: 6),
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: Colors.red[50],
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: Colors.red[100]!),
                ),
                child: Text(
                  'Penalty included: ${AppConfig.currencySymbol}${penaltyAmount.toStringAsFixed(2)}',
                  style: GoogleFonts.plusJakartaSans(
                    color: FastEmiTheme.red,
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
          ],
        ),
        trailing: Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              '${AppConfig.currencySymbol}${totalEmiAmount.toStringAsFixed(2)}',
              style: GoogleFonts.plusJakartaSans(
                fontWeight: FontWeight.w900,
                fontSize: 15,
                color: hasPenalty ? FastEmiTheme.red : FastEmiTheme.textDark,
              ),
            ),
            const SizedBox(height: 4),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  statusIcon,
                  size: 14,
                  color: statusColor,
                ),
                const SizedBox(width: 4),
                Text(
                  statusText,
                  style: GoogleFonts.plusJakartaSans(
                    color: statusColor,
                    fontWeight: FontWeight.w700,
                    fontSize: 11,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
