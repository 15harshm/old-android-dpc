import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import '../config/app_config.dart';
import '../config/fastemi_theme.dart';
import '../config/novaryn_theme.dart';
import 'novaryn_ui.dart';

class ImeiInputScreen extends StatelessWidget {
  final TextEditingController imei1Controller;
  final TextEditingController imei2Controller;
  final VoidCallback onSave;

  const ImeiInputScreen({
    super.key,
    required this.imei1Controller,
    required this.imei2Controller,
    required this.onSave,
  });

  @override
  Widget build(BuildContext context) {
    if (AppConfig.isNovaryn) {
      return _buildNovarynUI(context);
    }
    if (AppConfig.usesFastEmiUi) {
      return _buildFastEmiUI(context);
    }

    return Padding(
      padding: const EdgeInsets.all(32.0),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.phone_android,
            size: 100,
            color: const Color(0xFF2E3192),
          ),
          const SizedBox(height: 24),
          Text(
            AppConfig.appName,
            style: const TextStyle(
              fontSize: 36,
              fontWeight: FontWeight.bold,
              color: Color(0xFF2E3192),
            ),
          ),
          const SizedBox(height: 16),
          Text(
            "Device Setup",
            style: TextStyle(
              fontSize: 20,
              color: Colors.grey[600],
            ),
          ),
          const SizedBox(height: 32),
          Text(
            "Please enter your device IMEI to continue",
            style: TextStyle(
              fontSize: 16,
              color: Colors.grey[700],
            ),
          ),
          const SizedBox(height: 24),
          TextField(
            controller: imei1Controller,
            decoration: const InputDecoration(
              labelText: 'IMEI 1',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.phone_android),
            ),
            keyboardType: TextInputType.text,
          ),
          const SizedBox(height: 16),
          TextField(
            controller: imei2Controller,
            decoration: const InputDecoration(
              labelText: 'IMEI 2',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.phone_android),
            ),
            keyboardType: TextInputType.text,
          ),
          const SizedBox(height: 32),
          SizedBox(
            width: double.infinity,
            height: 56,
            child: ElevatedButton(
              onPressed: onSave,
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF2E3192),
                foregroundColor: Colors.white,
                elevation: 8,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16),
                ),
              ),
              child: const Text(
                'Save IMEI',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  // ── Novaryn flavor UI ──────────────────────────────────────────────────────
  // Distinct layout vs the FastEmi flavor: brand hero at the top (wordmark +
  // padlock mark), the form lives in a floating white card that overlaps the
  // hero, a glossy shield badge, and a gradient CTA. Same logic/callbacks.
  Widget _buildNovarynUI(BuildContext context) {
    TextField field(TextEditingController c, String label) => TextField(
          controller: c,
          keyboardType: TextInputType.text,
          style: NV.font(size: 15, weight: FontWeight.w600, color: NV.textDark),
          decoration: InputDecoration(
            labelText: label,
            hintText: 'Enter $label',
            prefixIcon: const Icon(Icons.pin_rounded),
          ),
        );

    return Scaffold(
      backgroundColor: NV.pageBg,
      body: NvPageBackground(
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(),
          slivers: [
            SliverToBoxAdapter(
              child: NvHeroBackground(
                borderRadius: const BorderRadius.only(
                  bottomLeft: Radius.circular(30),
                  bottomRight: Radius.circular(30),
                ),
                child: SafeArea(
                  bottom: false,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(20, 18, 20, 48),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const NvBrandLogo(markHeight: 44, wordSize: 22),
                        const SizedBox(height: 26),
                        Text(
                          'Device Setup',
                          style: NV.font(
                              size: 27,
                              weight: FontWeight.w700,
                              color: Colors.white),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          'Register this device to activate Novaryn Lock protection.',
                          style: NV.font(
                              size: 13.5,
                              color: Colors.white.withValues(alpha: 0.82),
                              height: 1.4),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            SliverToBoxAdapter(
              child: Transform.translate(
                offset: const Offset(0, -28),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
                  child: NvCard(
                    padding: const EdgeInsets.all(22),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Row(
                          children: [
                            const NvGlossyIcon(
                                icon: Icons.verified_user_rounded,
                                color: NV.blue,
                                size: 46),
                            const SizedBox(width: 14),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text('Verify your device',
                                      style: NV.font(
                                          size: 16.5,
                                          weight: FontWeight.w700)),
                                  const SizedBox(height: 2),
                                  Text('Enter both IMEI numbers to continue',
                                      style: NV.font(
                                          size: 12.5, color: NV.textMid)),
                                ],
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 22),
                        field(imei1Controller, 'IMEI 1'),
                        const SizedBox(height: 14),
                        field(imei2Controller, 'IMEI 2'),
                        const SizedBox(height: 22),
                        NvGradientButton(
                          label: 'Save & Continue',
                          icon: Icons.arrow_forward_rounded,
                          onPressed: onSave,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.only(top: 4, bottom: 24),
                child: Center(
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.shield_rounded,
                          size: 15, color: NV.textLight),
                      const SizedBox(width: 6),
                      Text('Secured by Novaryn Lock',
                          style:
                              NV.font(size: 12, color: NV.textLight)),
                    ],
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
      body: TweenAnimationBuilder<double>(
        tween: Tween<double>(begin: 0.0, end: 1.0),
        duration: const Duration(milliseconds: 900),
        builder: (context, value, child) {
          return Opacity(
            opacity: value,
            child: Transform.translate(
              offset: Offset(0.0, (1.0 - value) * 24.0),
              child: child,
            ),
          );
        },
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
                    padding: const EdgeInsets.fromLTRB(24, 32, 24, 40),
                    child: Column(
                      children: [
                        Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: FastEmiTheme.lime.withOpacity(0.15),
                            shape: BoxShape.circle,
                            border: Border.all(
                              color: FastEmiTheme.lime.withOpacity(0.3),
                              width: 2,
                            ),
                          ),
                          child: const Icon(
                            Icons.phone_android_rounded,
                            size: 48,
                            color: FastEmiTheme.lime,
                          ),
                        ),
                        const SizedBox(height: 20),
                        Text(
                          AppConfig.appName,
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 32,
                            fontWeight: FontWeight.w900,
                            color: Colors.white,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          "Device Setup",
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: Colors.white54,
                            letterSpacing: 1.2,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),

            // 2. Form Content Card
            SliverPadding(
              padding: const EdgeInsets.all(24.0),
              sliver: SliverToBoxAdapter(
                child: Container(
                  padding: const EdgeInsets.all(24.0),
                  decoration: BoxDecoration(
                    color: FastEmiTheme.cardBg,
                    borderRadius: BorderRadius.circular(16),
                    boxShadow: [
                      BoxShadow(
                        color: FastEmiTheme.bgDark.withOpacity(0.06),
                        blurRadius: 16,
                        offset: const Offset(0, 6),
                      ),
                    ],
                    border: Border.all(
                      color: FastEmiTheme.lime.withOpacity(0.12),
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        'PLEASE ENTER DEVICE IMEI',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 11,
                          fontWeight: FontWeight.w800,
                          color: FastEmiTheme.textLight,
                          letterSpacing: 1.8,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        "Input your device IMEI numbers below to continue setup.",
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                          color: FastEmiTheme.textMid,
                        ),
                      ),
                      const SizedBox(height: 24),
                      
                      // IMEI 1 Field
                      TextField(
                        controller: imei1Controller,
                        style: GoogleFonts.plusJakartaSans(
                          color: FastEmiTheme.textDark,
                          fontWeight: FontWeight.w600,
                        ),
                        decoration: InputDecoration(
                          labelText: 'IMEI 1',
                          labelStyle: GoogleFonts.plusJakartaSans(
                            color: FastEmiTheme.textLight,
                            fontWeight: FontWeight.w500,
                          ),
                          prefixIcon: const Icon(
                            Icons.phone_android_rounded,
                            color: FastEmiTheme.textLight,
                          ),
                          filled: true,
                          fillColor: FastEmiTheme.surfaceGrey,
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                            borderSide: BorderSide.none,
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                            borderSide: const BorderSide(
                              color: FastEmiTheme.lime,
                              width: 2,
                            ),
                          ),
                        ),
                        keyboardType: TextInputType.text,
                      ),
                      const SizedBox(height: 16),

                      // IMEI 2 Field
                      TextField(
                        controller: imei2Controller,
                        style: GoogleFonts.plusJakartaSans(
                          color: FastEmiTheme.textDark,
                          fontWeight: FontWeight.w600,
                        ),
                        decoration: InputDecoration(
                          labelText: 'IMEI 2',
                          labelStyle: GoogleFonts.plusJakartaSans(
                            color: FastEmiTheme.textLight,
                            fontWeight: FontWeight.w500,
                          ),
                          prefixIcon: const Icon(
                            Icons.phone_android_rounded,
                            color: FastEmiTheme.textLight,
                          ),
                          filled: true,
                          fillColor: FastEmiTheme.surfaceGrey,
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                            borderSide: BorderSide.none,
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                            borderSide: const BorderSide(
                              color: FastEmiTheme.lime,
                              width: 2,
                            ),
                          ),
                        ),
                        keyboardType: TextInputType.text,
                      ),
                      const SizedBox(height: 32),

                      // Save Button
                      SizedBox(
                        height: 54,
                        child: ElevatedButton(
                          onPressed: onSave,
                          style: ElevatedButton.styleFrom(
                            backgroundColor: FastEmiTheme.lime,
                            foregroundColor: FastEmiTheme.bgDark,
                            elevation: 4,
                            shadowColor: FastEmiTheme.lime.withOpacity(0.3),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(18),
                            ),
                          ),
                          child: Text(
                            'Save IMEI',
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 15,
                              fontWeight: FontWeight.w800,
                              color: FastEmiTheme.bgDark,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
