import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

class FastEmiTheme {
  // Color Palette
  static const Color bgDark = Color(0xFF0D0D0D);
  static const Color cardBg = Color(0xFFFFFFFF);
  static const Color lime = Color(0xFFC6F135);
  static const Color surfaceGrey = Color(0xFFF5F6FA);
  static const Color textDark = Color(0xFF0D0D0D);
  static const Color textMid = Color(0xFF6B7280);
  static const Color textLight = Color(0xFF9CA3AF);
  
  static const Color green = Color(0xFF10B981);
  static const Color amber = Color(0xFFF59E0B);
  static const Color red = Color(0xFFEF4444);
  static const Color cobalt = Color(0xFF0052FF);
  static const Color purple = Color(0xFF7C3AED);

  // Theme Data definition
  static ThemeData get themeData {
    return ThemeData(
      useMaterial3: true,
      scaffoldBackgroundColor: surfaceGrey,
      colorScheme: ColorScheme.fromSeed(
        seedColor: bgDark,
        primary: bgDark,
        secondary: lime,
        surface: cardBg,
        error: red,
        brightness: Brightness.light,
      ),
      fontFamily: GoogleFonts.plusJakartaSans().fontFamily,
      appBarTheme: const AppBarTheme(
        backgroundColor: bgDark,
        foregroundColor: Colors.white,
        elevation: 0,
      ),
    );
  }
}
