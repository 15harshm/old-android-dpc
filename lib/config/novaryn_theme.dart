import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Novaryn Lock design tokens — the single source of truth for colours,
/// gradients, radii, shadows and typography of the NOVARYN flavor only.
///
/// Ported from the Novaryn retailer app so the customer DPC app matches that
/// brand: deep-navy → royal-blue gradient hero areas with glowing cyan streaks,
/// a soft ice-blue page background, white rounded cards with hairline blue
/// borders, glossy colourful circular icons, and Poppins type.
///
/// Scoped strictly to the novaryn flavor (gated by AppConfig.isNovaryn at the
/// call sites). No other client references these tokens, so nothing here can
/// affect any other flavor's look.
abstract class NV {
  // ── Brand blues ────────────────────────────────────────────────────────────
  static const Color navy900 = Color(0xFF040B2B);
  static const Color navy800 = Color(0xFF071459);
  static const Color royal700 = Color(0xFF0B2A9E);
  static const Color royal600 = Color(0xFF1238C9);
  static const Color blue = Color(0xFF1E5BFF); // primary
  static const Color blue400 = Color(0xFF2F7BFF);
  static const Color cyan = Color(0xFF29B6FF); // accent ("Lock" wordmark)
  static const Color cyan300 = Color(0xFF5CD3FF);

  // ── Surfaces ───────────────────────────────────────────────────────────────
  static const Color pageBg = Color(0xFFEEF3FC);
  static const Color pageBg2 = Color(0xFFF7F9FF);
  static const Color card = Color(0xFFFFFFFF);
  static const Color cardBorder = Color(0xFFDCE6F8);
  static const Color divider = Color(0xFFE8EEF9);
  static const Color fieldFill = Color(0xFFF5F8FF);

  // ── Text ───────────────────────────────────────────────────────────────────
  static const Color textDark = Color(0xFF0B1A4A);
  static const Color textMid = Color(0xFF5A6A8E);
  static const Color textLight = Color(0xFF93A1BF);

  // ── Semantic / accent ──────────────────────────────────────────────────────
  static const Color green = Color(0xFF16B364);
  static const Color amber = Color(0xFFFF9F1C);
  static const Color orange = Color(0xFFFF7A1A);
  static const Color red = Color(0xFFF0384A);
  static const Color purple = Color(0xFF8B5CF6);
  static const Color pink = Color(0xFFEC4899);
  static const Color teal = Color(0xFF14B8A6);
  static const Color indigo = Color(0xFF4F46E5);

  // ── Gradients ──────────────────────────────────────────────────────────────
  static const LinearGradient headerGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [Color(0xFF07155E), Color(0xFF0D2DB0), Color(0xFF1A55F0)],
    stops: [0.0, 0.55, 1.0],
  );

  static const LinearGradient darkGradient = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [Color(0xFF06123F), Color(0xFF040B2B), Color(0xFF071A5C)],
    stops: [0.0, 0.55, 1.0],
  );

  static const LinearGradient primaryGradient = LinearGradient(
    begin: Alignment.centerLeft,
    end: Alignment.centerRight,
    colors: [Color(0xFF2160FF), Color(0xFF39A6FF)],
  );

  static const LinearGradient bannerGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [Color(0xFF061A6B), Color(0xFF0B2FB8), Color(0xFF0A1C72)],
  );

  static const LinearGradient pageGradient = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [Color(0xFFE9F0FD), Color(0xFFF5F8FF)],
  );

  // ── Radii ──────────────────────────────────────────────────────────────────
  static const double rCard = 18;
  static const double rTile = 14;
  static const double rField = 14;

  // ── Shadows ────────────────────────────────────────────────────────────────
  static List<BoxShadow> get cardShadow => [
        BoxShadow(
          color: const Color(0xFF1E5BFF).withValues(alpha: 0.07),
          blurRadius: 18,
          offset: const Offset(0, 6),
        ),
      ];

  static List<BoxShadow> glow(Color c, {double a = 0.35, double blur = 16}) => [
        BoxShadow(
          color: c.withValues(alpha: a),
          blurRadius: blur,
          offset: const Offset(0, 6),
        ),
      ];

  static BoxDecoration cardDecoration({
    double radius = rCard,
    Color? color,
    Color? border,
  }) =>
      BoxDecoration(
        color: color ?? card,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: border ?? cardBorder),
        boxShadow: cardShadow,
      );

  // ── Typography ─────────────────────────────────────────────────────────────
  static TextStyle font({
    double size = 14,
    FontWeight weight = FontWeight.w500,
    Color color = textDark,
    double? height,
    double? letterSpacing,
  }) =>
      GoogleFonts.poppins(
        fontSize: size,
        fontWeight: weight,
        color: color,
        height: height,
        letterSpacing: letterSpacing,
      );

  static TextStyle script({double size = 18, Color color = Colors.white}) =>
      GoogleFonts.kaushanScript(fontSize: size, color: color, height: 1.05);
}

/// Global Material theme for the Novaryn flavor of the customer app.
ThemeData buildNovarynTheme() {
  final base = ThemeData(useMaterial3: true, brightness: Brightness.light);
  final textTheme = GoogleFonts.poppinsTextTheme(base.textTheme).apply(
    bodyColor: NV.textDark,
    displayColor: NV.textDark,
  );

  OutlineInputBorder border(Color c, [double w = 1]) => OutlineInputBorder(
        borderRadius: BorderRadius.circular(NV.rField),
        borderSide: BorderSide(color: c, width: w),
      );

  return base.copyWith(
    scaffoldBackgroundColor: NV.pageBg,
    colorScheme: const ColorScheme(
      brightness: Brightness.light,
      primary: NV.blue,
      onPrimary: Colors.white,
      primaryContainer: Color(0xFFE3ECFF),
      onPrimaryContainer: NV.royal700,
      secondary: NV.cyan,
      onSecondary: Colors.white,
      secondaryContainer: Color(0xFFDDF3FF),
      onSecondaryContainer: NV.royal700,
      tertiary: NV.purple,
      onTertiary: Colors.white,
      error: NV.red,
      onError: Colors.white,
      errorContainer: Color(0xFFFFE1E4),
      onErrorContainer: Color(0xFF8C1020),
      surface: Colors.white,
      onSurface: NV.textDark,
      surfaceContainerHighest: NV.fieldFill,
      onSurfaceVariant: NV.textMid,
      outline: NV.cardBorder,
      outlineVariant: NV.divider,
      shadow: Colors.black,
      scrim: Colors.black,
      inverseSurface: NV.navy800,
      onInverseSurface: Colors.white,
      inversePrimary: NV.cyan300,
    ),
    textTheme: textTheme,
    primaryTextTheme: textTheme,
    iconTheme: const IconThemeData(color: NV.textDark),
    dividerTheme: const DividerThemeData(color: NV.divider, thickness: 1),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
      hintStyle: NV.font(size: 14, color: NV.textLight),
      labelStyle: NV.font(size: 14, color: NV.textMid),
      floatingLabelStyle: NV.font(size: 14, color: NV.blue),
      prefixIconColor: NV.textMid,
      suffixIconColor: NV.textMid,
      border: border(NV.cardBorder),
      enabledBorder: border(NV.cardBorder),
      focusedBorder: border(NV.blue, 1.6),
      errorBorder: border(NV.red),
      focusedErrorBorder: border(NV.red, 1.6),
      disabledBorder: border(NV.divider),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: NV.blue,
        foregroundColor: Colors.white,
        textStyle: NV.font(size: 15, weight: FontWeight.w600),
        padding: const EdgeInsets.symmetric(vertical: 15, horizontal: 28),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        elevation: 0,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: NV.blue,
        side: const BorderSide(color: NV.blue),
        textStyle: NV.font(size: 14, weight: FontWeight.w600),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: NV.blue,
        textStyle: NV.font(size: 14, weight: FontWeight.w600),
      ),
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: NV.royal700,
      foregroundColor: Colors.white,
      elevation: 0,
      centerTitle: true,
      titleTextStyle:
          NV.font(size: 18, weight: FontWeight.w600, color: Colors.white),
      iconTheme: const IconThemeData(color: Colors.white),
    ),
    floatingActionButtonTheme: const FloatingActionButtonThemeData(
      backgroundColor: NV.blue,
      foregroundColor: Colors.white,
      elevation: 4,
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: Colors.white,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      titleTextStyle: NV.font(size: 17, weight: FontWeight.w700),
      contentTextStyle: NV.font(size: 14, color: NV.textMid),
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(color: NV.blue),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: NV.navy800,
      contentTextStyle:
          NV.font(size: 13, weight: FontWeight.w500, color: Colors.white),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      elevation: 6,
    ),
  );
}
