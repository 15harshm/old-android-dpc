// tool/flavors/add_flavor.dart
//
// Guided CLI to onboard a new white-label flavor. It VALIDATES the parameters,
// APPENDS a new entry to tool/flavors/flavors.yaml (the single source of truth),
// optionally SCAFFOLDS android/app/src/<id>/ from a --reference flavor, and then
// REGENERATES the two artifacts by delegating to gen_flavors.dart.
//
// Pure Dart: only dart:io + the `yaml` package (for reading existing ids).
//
// Usage:
//   dart run tool/flavors/add_flavor.dart \
//       --name "Acme Secure" --id acme --domain acme.com \
//       [--currency "$"] [--term EMI] \
//       [--app-description "..."] [--accessibility-description "..."] \
//       [--api-base "https://acme.com/api2"] [--socket-base "https://socket.acme.com"] \
//       [--fastemi-ui] [--payment-enable] [--notify-enroll-update] \
//       [--primary-color "#101418"] [--accent-color "#39FFB0"] \
//       [--reference fastemi] [--no-analyze] [--verify]
//
// Required: --name, --id, --domain. Everything else falls back to the defaults
// declared in flavors.yaml. --primary-color / --accent-color are validated and
// stored in the YAML for the FastEmiTheme recolor follow-up (not yet rendered).
// By default add_flavor runs `flutter analyze` (Dart-only) after regenerating;
// pass --no-analyze to skip it, or --verify to ALSO run the per-flavor Gradle
// `assemble<Flavor>Debug --dry-run` guard (off by default so it never launches a
// build unexpectedly).

import 'dart:io';

import 'package:yaml/yaml.dart';

final RegExp _idPattern = RegExp(r'^[a-z][a-z0-9]+$');
final RegExp _domainPattern = RegExp(r'^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$');
final RegExp _hexPattern = RegExp(r'^#[0-9a-fA-F]{6}$');

void main(List<String> args) {
  final Map<String, dynamic> opts = _parseArgs(args);

  final String name = _require(opts, 'name');
  final String id = _require(opts, 'id');
  final String domain = _require(opts, 'domain');

  final Directory root = _projectRoot();
  final File yamlFile = File('${root.path}/tool/flavors/flavors.yaml');
  if (!yamlFile.existsSync()) {
    _fail('flavors.yaml not found at ${yamlFile.path}');
  }

  // --- validation -----------------------------------------------------------
  if (!_idPattern.hasMatch(id)) {
    _fail('Invalid --id "$id": must match ${_idPattern.pattern}.');
  }
  if (!_domainPattern.hasMatch(domain)) {
    _fail('Invalid --domain "$domain": expected e.g. acme.com.');
  }
  final String? primary = opts['primary-color'] as String?;
  final String? accent = opts['accent-color'] as String?;
  if (primary != null && !_hexPattern.hasMatch(primary)) {
    _fail('Invalid --primary-color "$primary": expected #RRGGBB.');
  }
  if (accent != null && !_hexPattern.hasMatch(accent)) {
    _fail('Invalid --accent-color "$accent": expected #RRGGBB.');
  }

  final Set<String> existingIds = _existingIds(yamlFile);
  if (existingIds.contains(id)) {
    _fail('Duplicate flavor id "$id" — already present in flavors.yaml.');
  }

  final String? reference = opts['reference'] as String?;
  if (reference != null) {
    final Directory refDir = Directory('${root.path}/android/app/src/$reference');
    if (!refDir.existsSync()) {
      _fail('--reference "$reference" not found at android/app/src/$reference');
    }
    if (!existingIds.contains(reference)) {
      stderr.writeln('add_flavor: WARNING — --reference "$reference" is not a '
          'known flavor id, but its src dir exists; using it for scaffolding.');
    }
  }

  final bool fastemiUi = opts['fastemi-ui'] == true;
  final bool paymentEnable = opts['payment-enable'] == true;
  final bool notifyEnroll = opts['notify-enroll-update'] == true;
  final String appDescription = (opts['app-description'] as String?) ??
      '$name DPC - Required for monitoring app usage and device control';
  final String accessibilityDescription =
      (opts['accessibility-description'] as String?) ??
          '$name DPC - Required for monitoring app usage and device control';

  // --- append the entry to flavors.yaml -------------------------------------
  final StringBuffer entry = StringBuffer();
  entry.writeln();
  entry.writeln('  - id: $id');
  entry.writeln('    appName: ${_yamlStr(name)}');
  entry.writeln('    domain: ${_yamlStr(domain)}');
  if (opts['api-base'] != null) {
    entry.writeln('    apiBase: ${_yamlStr(opts['api-base'] as String)}');
  }
  if (opts['socket-base'] != null) {
    entry.writeln('    socketBase: ${_yamlStr(opts['socket-base'] as String)}');
  }
  if (opts['currency'] != null) {
    entry.writeln('    currency: ${_yamlStr(opts['currency'] as String)}');
  }
  if (opts['term'] != null) {
    entry.writeln('    term: ${_yamlStr(opts['term'] as String)}');
  }
  if (fastemiUi) entry.writeln('    usesFastEmiUi: true');
  if (paymentEnable) entry.writeln('    paymentEnable: true');
  if (notifyEnroll) entry.writeln('    notifyEnrollUpdate: true');
  if (primary != null) entry.writeln('    primaryColor: ${_yamlStr(primary)}');
  if (accent != null) entry.writeln('    accentColor: ${_yamlStr(accent)}');
  entry.writeln('    appDescription: ${_yamlStr(appDescription)}');
  entry.writeln(
      '    accessibilityDescription: ${_yamlStr(accessibilityDescription)}');

  final String current = yamlFile.readAsStringSync();
  final String updated =
      current.endsWith('\n') ? '$current${entry.toString()}' : '$current\n${entry.toString()}';
  yamlFile.writeAsStringSync(updated);
  stdout.writeln('add_flavor: appended "$id" to flavors.yaml');

  // --- scaffold android/app/src/<id>/ from the reference --------------------
  if (reference != null) {
    _scaffold(root, reference, id, fastemiUi);
  }

  // --- regenerate the artifacts ---------------------------------------------
  final ProcessResult gen = Process.runSync(
    'dart',
    <String>['run', 'tool/flavors/gen_flavors.dart'],
    workingDirectory: root.path,
  );
  stdout.write(gen.stdout);
  stderr.write(gen.stderr);
  if (gen.exitCode != 0) {
    _fail('gen_flavors.dart failed (exit ${gen.exitCode}). '
        'The flavors.yaml entry was written — fix it and re-run gen_flavors.');
  }

  // --- optional verification ------------------------------------------------
  if (opts['no-analyze'] != true) {
    stdout.writeln('add_flavor: running `flutter analyze` ...');
    final ProcessResult an = Process.runSync('flutter', <String>['analyze'],
        workingDirectory: root.path);
    stdout.write(an.stdout);
    stderr.write(an.stderr);
  }
  if (opts['verify'] == true) {
    final String cap = id[0].toUpperCase() + id.substring(1);
    stdout.writeln('add_flavor: running Gradle dry-run for :app:assemble${cap}Debug ...');
    final bool win = Platform.isWindows;
    final ProcessResult gr = Process.runSync(
      win ? 'cmd' : './gradlew',
      win
          ? <String>['/c', 'gradlew.bat', ':app:assemble${cap}Debug', '--dry-run']
          : <String>[':app:assemble${cap}Debug', '--dry-run'],
      workingDirectory: '${root.path}/android',
    );
    stdout.write(gr.stdout);
    stderr.write(gr.stderr);
  }

  // --- next steps (cannot be automated) -------------------------------------
  stdout.writeln('');
  stdout.writeln('add_flavor: DONE. Two manual steps remain for "$id":');
  stdout.writeln('  1. Replace android/app/src/$id/google-services.PLACEHOLDER.json');
  stdout.writeln('     with the client\'s REAL Firebase google-services.json.');
  stdout.writeln('  2. Drop the client\'s REAL ic_launcher bitmaps into');
  stdout.writeln('     android/app/src/$id/res/mipmap-*/ (placeholders copied from '
      '${reference ?? '<none>'}).');
}

// ---------------------------------------------------------------------------
// Scaffolding
// ---------------------------------------------------------------------------

void _scaffold(Directory root, String reference, String id, bool fastemiUi) {
  final String base = '${root.path}/android/app/src';
  // Copy mipmap-* icon dirs as placeholders.
  final Directory refRes = Directory('$base/$reference/res');
  if (refRes.existsSync()) {
    for (final FileSystemEntity e in refRes.listSync()) {
      if (e is Directory && e.path.split(Platform.pathSeparator).last.startsWith('mipmap')) {
        final String dirName = e.path.split(Platform.pathSeparator).last;
        _copyDir(e, Directory('$base/$id/res/$dirName'));
      }
    }
  }
  // Copy kiosk_layout.xml for premium flavors, if the reference has one.
  if (fastemiUi) {
    final File refKiosk = File('$base/$reference/res/layout/kiosk_layout.xml');
    if (refKiosk.existsSync()) {
      final File dst = File('$base/$id/res/layout/kiosk_layout.xml');
      dst.parent.createSync(recursive: true);
      dst.writeAsBytesSync(refKiosk.readAsBytesSync());
    }
  }
  // Drop a placeholder Firebase marker so the wrong project can never ship silently.
  final File placeholder = File('$base/$id/google-services.PLACEHOLDER.json');
  placeholder.parent.createSync(recursive: true);
  placeholder.writeAsStringSync(
      '{\n  "_comment": "PLACEHOLDER — replace with the real google-services.json '
      'for flavor \\"$id\\" before building for production."\n}\n');
  stdout.writeln('add_flavor: scaffolded android/app/src/$id/ from "$reference".');
}

void _copyDir(Directory src, Directory dst) {
  dst.createSync(recursive: true);
  for (final FileSystemEntity e in src.listSync()) {
    final String name = e.path.split(Platform.pathSeparator).last;
    if (e is File) {
      File('${dst.path}/$name').writeAsBytesSync(e.readAsBytesSync());
    } else if (e is Directory) {
      _copyDir(e, Directory('${dst.path}/$name'));
    }
  }
}

// ---------------------------------------------------------------------------
// Arg parsing / helpers
// ---------------------------------------------------------------------------

const Set<String> _flags = <String>{
  'fastemi-ui',
  'payment-enable',
  'notify-enroll-update',
  'no-analyze',
  'verify',
};

Map<String, dynamic> _parseArgs(List<String> args) {
  final Map<String, dynamic> out = <String, dynamic>{};
  for (int i = 0; i < args.length; i++) {
    final String a = args[i];
    if (!a.startsWith('--')) {
      _fail('Unexpected argument "$a".');
    }
    final String key = a.substring(2);
    if (_flags.contains(key)) {
      out[key] = true;
    } else {
      if (i + 1 >= args.length) _fail('Missing value for --$key.');
      out[key] = args[++i];
    }
  }
  return out;
}

String _require(Map<String, dynamic> opts, String key) {
  final dynamic v = opts[key];
  if (v is String && v.trim().isNotEmpty) {
    return v;
  }
  _fail('Missing required --$key. Run with --name, --id and --domain at minimum.');
}

Set<String> _existingIds(File yamlFile) {
  final dynamic doc = loadYaml(yamlFile.readAsStringSync());
  final Set<String> ids = <String>{};
  if (doc is YamlMap && doc['flavors'] is YamlList) {
    for (final dynamic f in doc['flavors'] as YamlList) {
      if (f is YamlMap && f['id'] != null) ids.add(f['id'].toString());
    }
  }
  return ids;
}

/// Emit a double-quoted YAML scalar with the minimal escaping we need.
String _yamlStr(String s) =>
    '"${s.replaceAll('\\', r'\\').replaceAll('"', r'\"')}"';

Never _fail(String message) {
  stderr.writeln('add_flavor: ERROR — $message');
  exit(1);
}

Directory _projectRoot() {
  Directory dir = File.fromUri(Platform.script).parent;
  while (true) {
    if (File('${dir.path}/pubspec.yaml').existsSync()) return dir;
    final Directory parent = dir.parent;
    if (parent.path == dir.path) return Directory.current;
    dir = parent;
  }
}
