// tool/flavors/gen_flavors.dart
//
// Reads the single source of truth `tool/flavors/flavors.yaml`, merges the
// `defaults:` block into every flavor, validates, resolves the apiBase/socketBase
// URL patterns, and emits two CHECKED-IN generated artifacts:
//
//   * android/flavors.gen.json          — flat resolved array, consumed by Gradle
//   * lib/config/flavor_registry.g.dart — const kFlavorRegistry, consumed by Dart
//
// Pure Dart: only dart:io + the `yaml` package. Run from the project root:
//     dart run tool/flavors/gen_flavors.dart
//
// The output is deterministic so a re-run leaves a clean `git diff` (idempotent).

import 'dart:convert';
import 'dart:io';

import 'package:yaml/yaml.dart';

/// Fields carried in the flat `android/flavors.gen.json` array (Gradle needs
/// these). Order here is the object-key order in the emitted JSON.
const List<String> jsonFields = <String>[
  'id',
  'appName',
  'domain',
  'apiBase',
  'socketBase',
  'appDescription',
  'accessibilityDescription',
];

/// Valid Gradle flavor name AND Dart-map key.
final RegExp idPattern = RegExp(r'^[a-z][a-z0-9]+$');

void main(List<String> args) {
  final Directory root = _projectRoot();
  final File yamlFile = File('${root.path}/tool/flavors/flavors.yaml');
  if (!yamlFile.existsSync()) {
    _fail('flavors.yaml not found at ${yamlFile.path}');
  }

  final dynamic doc = loadYaml(yamlFile.readAsStringSync());
  if (doc is! YamlMap) {
    _fail('flavors.yaml root must be a mapping.');
  }

  final Map<String, dynamic> defaults = _asPlainMap(doc['defaults']);
  final dynamic rawFlavors = doc['flavors'];
  if (rawFlavors is! YamlList || rawFlavors.isEmpty) {
    _fail('flavors.yaml must contain a non-empty `flavors:` list.');
  }

  final List<Map<String, dynamic>> resolved = <Map<String, dynamic>>[];
  final Set<String> seenIds = <String>{};

  for (final dynamic rawEntry in rawFlavors) {
    final Map<String, dynamic> entry = _resolve(_asPlainMap(rawEntry), defaults);
    final String id = (entry['id'] ?? '').toString();

    // --- validation ---------------------------------------------------------
    if (!idPattern.hasMatch(id)) {
      _fail('Invalid flavor id "$id": must match ${idPattern.pattern} '
          '(a valid Gradle flavor name and Dart-map key).');
    }
    if (!seenIds.add(id)) {
      _fail('Duplicate flavor id "$id" in flavors.yaml.');
    }
    final String domain = (entry['domain'] ?? '').toString();
    if (domain.trim().isEmpty) {
      _fail('Flavor "$id" has an empty domain.');
    }
    if (domain.contains('{') || domain.contains(' ')) {
      _fail('Flavor "$id" has a malformed domain "$domain".');
    }

    // --- resolve URL patterns ({domain} substitution) -----------------------
    final String apiBase = _resolveUrl(entry['apiBase'], domain, id, 'apiBase');
    final String socketBase =
        _resolveUrl(entry['socketBase'], domain, id, 'socketBase');
    entry['apiBase'] = apiBase;
    entry['socketBase'] = socketBase;

    resolved.add(entry);
  }

  _writeJson(root, resolved);
  _writeDart(root, resolved);

  stdout.writeln('gen_flavors: wrote ${resolved.length} flavors -> '
      'android/flavors.gen.json + lib/config/flavor_registry.g.dart');
}

// ---------------------------------------------------------------------------
// Resolution helpers
// ---------------------------------------------------------------------------

/// Merge a flavor entry over the defaults (flavor keys win).
Map<String, dynamic> _resolve(
    Map<String, dynamic> entry, Map<String, dynamic> defaults) {
  final Map<String, dynamic> out = <String, dynamic>{...defaults, ...entry};
  // applicationId lives only in defaults today; it is not needed downstream but
  // is kept out of the generated outputs (Gradle hardcodes com.renew.jss).
  out.remove('applicationId');
  return out;
}

String _resolveUrl(dynamic raw, String domain, String id, String field) {
  final String pattern = (raw ?? '').toString();
  if (pattern.trim().isEmpty) {
    _fail('Flavor "$id" resolved an empty $field.');
  }
  final String resolvedUrl = pattern.replaceAll('{domain}', domain);
  if (resolvedUrl.contains('{')) {
    _fail('Flavor "$id" $field did not fully resolve: "$resolvedUrl".');
  }
  if (!resolvedUrl.startsWith('http')) {
    _fail('Flavor "$id" $field is not a valid URL: "$resolvedUrl".');
  }
  return resolvedUrl;
}

// ---------------------------------------------------------------------------
// Emitters
// ---------------------------------------------------------------------------

void _writeJson(Directory root, List<Map<String, dynamic>> flavors) {
  final List<Map<String, dynamic>> flat = flavors
      .map((Map<String, dynamic> f) => <String, dynamic>{
            for (final String k in jsonFields) k: f[k],
          })
      .toList();
  final String json = const JsonEncoder.withIndent('  ').convert(flat);
  final File out = File('${root.path}/android/flavors.gen.json');
  out.writeAsStringSync('$json\n');
}

void _writeDart(Directory root, List<Map<String, dynamic>> flavors) {
  final StringBuffer b = StringBuffer();
  b.writeln('// GENERATED by tool/flavors/gen_flavors.dart — DO NOT EDIT.');
  b.writeln('//');
  b.writeln('// Source of truth: tool/flavors/flavors.yaml');
  b.writeln('// Regenerate with: dart run tool/flavors/gen_flavors.dart');
  b.writeln();
  b.writeln('/// A fully-resolved white-label flavor entry.');
  b.writeln('class FlavorEntry {');
  b.writeln('  final String id;');
  b.writeln('  final String appName;');
  b.writeln('  final String domain;');
  b.writeln('  final String apiBase;');
  b.writeln('  final String socketBase;');
  b.writeln('  final String appDescription;');
  b.writeln('  final String accessibilityDescription;');
  b.writeln('  final String currency;');
  b.writeln('  final String term;');
  b.writeln('  final bool usesFastEmiUi;');
  b.writeln('  final bool paymentEnable;');
  b.writeln('  final bool notifyEnrollUpdate;');
  b.writeln();
  b.writeln('  const FlavorEntry({');
  b.writeln('    required this.id,');
  b.writeln('    required this.appName,');
  b.writeln('    required this.domain,');
  b.writeln('    required this.apiBase,');
  b.writeln('    required this.socketBase,');
  b.writeln('    required this.appDescription,');
  b.writeln('    required this.accessibilityDescription,');
  b.writeln('    required this.currency,');
  b.writeln('    required this.term,');
  b.writeln('    required this.usesFastEmiUi,');
  b.writeln('    required this.paymentEnable,');
  b.writeln('    required this.notifyEnrollUpdate,');
  b.writeln('  });');
  b.writeln('}');
  b.writeln();
  b.writeln('const Map<String, FlavorEntry> kFlavorRegistry = <String, FlavorEntry>{');
  for (final Map<String, dynamic> f in flavors) {
    final String id = f['id'].toString();
    b.writeln("  '${_dartEsc(id)}': FlavorEntry(");
    b.writeln("    id: '${_dartEsc(id)}',");
    b.writeln("    appName: '${_dartEsc(f['appName'].toString())}',");
    b.writeln("    domain: '${_dartEsc(f['domain'].toString())}',");
    b.writeln("    apiBase: '${_dartEsc(f['apiBase'].toString())}',");
    b.writeln("    socketBase: '${_dartEsc(f['socketBase'].toString())}',");
    b.writeln(
        "    appDescription: '${_dartEsc(f['appDescription'].toString())}',");
    b.writeln("    accessibilityDescription: "
        "'${_dartEsc(f['accessibilityDescription'].toString())}',");
    b.writeln("    currency: '${_dartEsc(f['currency'].toString())}',");
    b.writeln("    term: '${_dartEsc(f['term'].toString())}',");
    b.writeln('    usesFastEmiUi: ${_asBool(f['usesFastEmiUi'])},');
    b.writeln('    paymentEnable: ${_asBool(f['paymentEnable'])},');
    b.writeln('    notifyEnrollUpdate: ${_asBool(f['notifyEnrollUpdate'])},');
    b.writeln('  ),');
  }
  b.writeln('};');

  final File out = File('${root.path}/lib/config/flavor_registry.g.dart');
  out.writeAsStringSync(b.toString());
}

// ---------------------------------------------------------------------------
// Small utilities
// ---------------------------------------------------------------------------

Map<String, dynamic> _asPlainMap(dynamic node) {
  if (node == null) return <String, dynamic>{};
  if (node is YamlMap) {
    return node.map<String, dynamic>(
        (dynamic k, dynamic v) => MapEntry<String, dynamic>(k.toString(), v));
  }
  if (node is Map) {
    return node.map<String, dynamic>(
        (dynamic k, dynamic v) => MapEntry<String, dynamic>(k.toString(), v));
  }
  _fail('Expected a mapping but got: $node');
}

bool _asBool(dynamic v) => v == true || v?.toString() == 'true';

/// Escape a Dart single-quoted string literal.
String _dartEsc(String s) => s
    .replaceAll('\\', r'\\')
    .replaceAll("'", r"\'")
    .replaceAll(r'$', r'\$')
    .replaceAll('\n', r'\n');

Never _fail(String message) {
  stderr.writeln('gen_flavors: ERROR — $message');
  exit(1);
}

/// Walk up from this script's directory until a pubspec.yaml is found.
Directory _projectRoot() {
  Directory dir = File.fromUri(Platform.script).parent;
  while (true) {
    if (File('${dir.path}/pubspec.yaml').existsSync()) return dir;
    final Directory parent = dir.parent;
    if (parent.path == dir.path) {
      // Fall back to the current working directory.
      return Directory.current;
    }
    dir = parent;
  }
}
