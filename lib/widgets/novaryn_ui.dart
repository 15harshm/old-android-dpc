import 'package:flutter/material.dart';

import '../config/novaryn_theme.dart';

// ═════════════════════════════════════════════════════════════════════════════
//  Novaryn Lock — shared UI building blocks (customer DPC app, novaryn flavor).
//  A lean port of the retailer app's novaryn_ui.dart: only the pieces the four
//  customer screens need, with no go_router / retailer-only widgets.
// ═════════════════════════════════════════════════════════════════════════════

/// Glowing cyan light streaks + soft radial glows painted over hero gradients.
class NvStreaksPainter extends CustomPainter {
  final double intensity;
  const NvStreaksPainter({this.intensity = 1});

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width, h = size.height;
    if (w <= 0 || h <= 0) return;

    void radial(Offset c, double r, Color color) {
      canvas.drawCircle(
        c,
        r,
        Paint()
          ..shader = RadialGradient(
            colors: [color, color.withValues(alpha: 0)],
          ).createShader(Rect.fromCircle(center: c, radius: r)),
      );
    }

    radial(Offset(w * 0.88, h * 0.15), w * 0.55,
        NV.cyan.withValues(alpha: 0.28 * intensity));
    radial(Offset(w * 0.05, h * 1.05), w * 0.5,
        NV.blue400.withValues(alpha: 0.30 * intensity));

    final shader = LinearGradient(
      colors: [
        Colors.white.withValues(alpha: 0),
        NV.cyan300.withValues(alpha: 0.75 * intensity),
        Colors.white.withValues(alpha: 0.85 * intensity),
        NV.cyan.withValues(alpha: 0),
      ],
      stops: const [0.0, 0.45, 0.7, 1.0],
    ).createShader(Rect.fromLTWH(0, 0, w, h));

    for (var i = 0; i < 4; i++) {
      final p = Path()
        ..moveTo(-w * 0.1, h * (1.0 - i * 0.06))
        ..cubicTo(
          w * 0.35,
          h * (0.62 - i * 0.05),
          w * 0.62,
          h * (1.12 - i * 0.07),
          w * 1.1,
          h * (0.30 - i * 0.05),
        );
      canvas.drawPath(
        p,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = i == 1 ? 7 : 4
          ..color = NV.cyan.withValues(alpha: 0.16 * intensity)
          ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 6),
      );
      canvas.drawPath(
        p,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = i == 1 ? 1.8 : 0.9
          ..shader = shader,
      );
    }
  }

  @override
  bool shouldRepaint(NvStreaksPainter old) => old.intensity != intensity;
}

/// Gradient hero background with streaks. Use as a decorated container.
class NvHeroBackground extends StatelessWidget {
  final Widget child;
  final BorderRadius? borderRadius;
  final Gradient gradient;
  final double intensity;

  const NvHeroBackground({
    super.key,
    required this.child,
    this.borderRadius,
    this.gradient = NV.headerGradient,
    this.intensity = 1,
  });

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: borderRadius ?? BorderRadius.zero,
      child: DecoratedBox(
        decoration: BoxDecoration(gradient: gradient),
        child: CustomPaint(
          painter: NvStreaksPainter(intensity: intensity),
          child: child,
        ),
      ),
    );
  }
}

/// "Novaryn / Lock" wordmark (cyan "Lock").
class NvWordmark extends StatelessWidget {
  final double size;
  final bool showTagline;
  const NvWordmark({super.key, this.size = 22, this.showTagline = true});

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Novaryn',
          style: NV.font(
            size: size,
            weight: FontWeight.w700,
            color: Colors.white,
            height: 1.0,
            letterSpacing: -0.3,
          ),
        ),
        ShaderMask(
          shaderCallback: (r) => const LinearGradient(
            colors: [NV.cyan300, NV.cyan, NV.blue400],
          ).createShader(r),
          child: Text(
            'Lock',
            style: NV.font(
              size: size * 0.86,
              weight: FontWeight.w700,
              color: Colors.white,
              height: 1.05,
            ),
          ),
        ),
        if (showTagline) ...[
          SizedBox(height: size * 0.12),
          Text(
            'LOCK  •  PROTECT  •  CONTROL',
            style: NV.font(
              size: (size * 0.30).clamp(7, 12).toDouble(),
              weight: FontWeight.w500,
              color: Colors.white.withValues(alpha: 0.75),
              letterSpacing: 1.2,
            ),
          ),
        ],
      ],
    );
  }
}

/// Brand mark (image) + wordmark row. Falls back to a glass padlock tile if the
/// bundled asset is missing, so it never breaks the build on other setups.
class NvBrandLogo extends StatelessWidget {
  final double markHeight;
  final double wordSize;
  final bool showTagline;
  const NvBrandLogo({
    super.key,
    this.markHeight = 40,
    this.wordSize = 20,
    this.showTagline = true,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Image.asset(
          'assets/novaryn_mark.png',
          height: markHeight,
          errorBuilder: (_, __, ___) => Container(
            width: markHeight,
            height: markHeight,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Colors.white.withValues(alpha: 0.12),
              border: Border.all(color: NV.cyan300.withValues(alpha: 0.6)),
            ),
            child: Icon(Icons.lock_rounded,
                color: NV.cyan300, size: markHeight * 0.52),
          ),
        ),
        const SizedBox(width: 8),
        NvWordmark(size: wordSize, showTagline: showTagline),
      ],
    );
  }
}

/// Round translucent glass button used on hero headers.
class NvGlassIconButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback? onTap;
  final double size;
  final bool busy;

  const NvGlassIconButton({
    super.key,
    required this.icon,
    this.onTap,
    this.size = 42,
    this.busy = false,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: Colors.white.withValues(alpha: 0.10),
          border: Border.all(color: Colors.white.withValues(alpha: 0.22)),
        ),
        child: busy
            ? Padding(
                padding: EdgeInsets.all(size * 0.28),
                child: const CircularProgressIndicator(
                    color: Colors.white, strokeWidth: 2),
              )
            : Icon(icon, color: Colors.white, size: size * 0.52),
      ),
    );
  }
}

/// Ice-blue page background gradient.
class NvPageBackground extends StatelessWidget {
  final Widget child;
  const NvPageBackground({super.key, required this.child});

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: const BoxDecoration(gradient: NV.pageGradient),
        child: child,
      );
}

/// Glossy 3D-ish circular icon.
class NvGlossyIcon extends StatelessWidget {
  final IconData icon;
  final Color color;
  final double size;

  const NvGlossyIcon({
    super.key,
    required this.icon,
    required this.color,
    this.size = 48,
  });

  @override
  Widget build(BuildContext context) {
    final hsl = HSLColor.fromColor(color);
    final light =
        hsl.withLightness((hsl.lightness + 0.16).clamp(0.0, 1.0)).toColor();
    final dark =
        hsl.withLightness((hsl.lightness - 0.12).clamp(0.0, 1.0)).toColor();
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [light, color, dark],
          stops: const [0.0, 0.55, 1.0],
        ),
        boxShadow: [
          BoxShadow(
            color: color.withValues(alpha: 0.38),
            blurRadius: size * 0.3,
            offset: Offset(0, size * 0.1),
          ),
        ],
      ),
      child: Stack(
        alignment: Alignment.center,
        children: [
          Positioned(
            top: size * 0.06,
            child: Container(
              width: size * 0.66,
              height: size * 0.34,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(size),
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.white.withValues(alpha: 0.55),
                    Colors.white.withValues(alpha: 0.0),
                  ],
                ),
              ),
            ),
          ),
          Icon(icon, color: Colors.white, size: size * 0.5),
        ],
      ),
    );
  }
}

/// Soft tinted circle icon (info rows / section titles).
class NvTintIcon extends StatelessWidget {
  final IconData icon;
  final Color color;
  final double size;
  const NvTintIcon({
    super.key,
    required this.icon,
    this.color = NV.blue,
    this.size = 40,
  });

  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(size * 0.3),
          color: color.withValues(alpha: 0.12),
        ),
        child: Icon(icon, color: color, size: size * 0.54),
      );
}

/// White rounded card with hairline border and soft blue shadow.
class NvCard extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry padding;
  final EdgeInsetsGeometry? margin;
  final VoidCallback? onTap;
  final double radius;
  final Color? color;
  final Color? borderColor;

  const NvCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
    this.margin,
    this.onTap,
    this.radius = NV.rCard,
    this.color,
    this.borderColor,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: margin,
      decoration: BoxDecoration(
        color: color ?? Colors.white,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: borderColor ?? NV.cardBorder),
        boxShadow: NV.cardShadow,
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(radius),
          child: Padding(padding: padding, child: child),
        ),
      ),
    );
  }
}

/// Uppercase tracked section label (e.g. "OVERVIEW").
class NvSectionLabel extends StatelessWidget {
  final String text;
  final EdgeInsetsGeometry padding;
  const NvSectionLabel(
    this.text, {
    super.key,
    this.padding = const EdgeInsets.fromLTRB(4, 0, 4, 10),
  });

  @override
  Widget build(BuildContext context) => Padding(
        padding: padding,
        child: Row(
          children: [
            Container(
              width: 4,
              height: 14,
              decoration: BoxDecoration(
                gradient: NV.primaryGradient,
                borderRadius: BorderRadius.circular(4),
              ),
            ),
            const SizedBox(width: 8),
            Text(
              text.toUpperCase(),
              style: NV.font(
                size: 12,
                weight: FontWeight.w700,
                color: NV.textMid,
                letterSpacing: 1.4,
              ),
            ),
          ],
        ),
      );
}

/// Full-width gradient primary button with glow.
class NvGradientButton extends StatelessWidget {
  final String label;
  final IconData? icon;
  final VoidCallback? onPressed;
  final bool loading;
  final double height;
  final Gradient gradient;

  const NvGradientButton({
    super.key,
    required this.label,
    this.icon,
    this.onPressed,
    this.loading = false,
    this.height = 54,
    this.gradient = NV.primaryGradient,
  });

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null && !loading;
    return Opacity(
      opacity: enabled || loading ? 1 : 0.5,
      child: Container(
        height: height,
        decoration: BoxDecoration(
          gradient: enabled || loading
              ? gradient
              : const LinearGradient(
                  colors: [Color(0xFFB9C4DC), Color(0xFFB9C4DC)]),
          borderRadius: BorderRadius.circular(16),
          boxShadow: enabled ? NV.glow(NV.blue400, a: 0.40, blur: 18) : null,
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: enabled ? onPressed : null,
            borderRadius: BorderRadius.circular(16),
            child: Center(
              child: loading
                  ? const SizedBox(
                      width: 22,
                      height: 22,
                      child: CircularProgressIndicator(
                          color: Colors.white, strokeWidth: 2.4),
                    )
                  : Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (icon != null) ...[
                          Icon(icon, color: Colors.white, size: 22),
                          const SizedBox(width: 10),
                        ],
                        Flexible(
                          child: FittedBox(
                            fit: BoxFit.scaleDown,
                            child: Text(
                              label,
                              maxLines: 1,
                              style: NV.font(
                                size: 16,
                                weight: FontWeight.w600,
                                color: Colors.white,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Small rounded status pill.
class NvPill extends StatelessWidget {
  final String label;
  final Color color;
  final IconData? icon;
  final bool solid;

  const NvPill({
    super.key,
    required this.label,
    this.color = NV.blue,
    this.icon,
    this.solid = false,
  });

  @override
  Widget build(BuildContext context) {
    final fg = solid ? Colors.white : color;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: solid ? color : color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
        border: solid ? null : Border.all(color: color.withValues(alpha: 0.28)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 12, color: fg),
            const SizedBox(width: 5),
          ],
          Text(label,
              style: NV.font(size: 11, weight: FontWeight.w600, color: fg)),
        ],
      ),
    );
  }
}

/// Label → value line inside detail cards (value right-aligned).
class NvInfoLine extends StatelessWidget {
  final String label;
  final String value;
  final bool last;
  const NvInfoLine(this.label, this.value, {super.key, this.last = false});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(bottom: last ? 0 : 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 4,
            child: Text(label,
                style: NV.font(
                    size: 13, weight: FontWeight.w500, color: NV.textMid)),
          ),
          const SizedBox(width: 10),
          Expanded(
            flex: 6,
            child: Text(
              value.isEmpty ? 'N/A' : value,
              textAlign: TextAlign.end,
              style: NV.font(
                  size: 13.5, weight: FontWeight.w600, color: NV.textDark),
            ),
          ),
        ],
      ),
    );
  }
}
