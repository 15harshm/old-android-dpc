import 'package:flutter/foundation.dart';
import '../data/policy_data.dart';
import 'policy_diff_engine.dart';
import '../../../core/applied_policy_state.dart';

class PolicyEnforcementManager {
  static bool _isInitialized = false;

  static Future<void> initialize() async {
    if (_isInitialized) return;
    
    await AppliedPolicyState.initialize();
    _isInitialized = true;
    
    if (kDebugMode) {
      print('🚀 Policy Enforcement Manager initialized (Native Only)');
    }
  }

  static Future<void> _ensureInitialized() async {
    if (!_isInitialized) {
      await initialize();
    }
  }

  // Policy enforcement is handled by native Android side via socket/FCM
  // Flutter side only tracks state for UI display
  static Future<void> analyzeAndLogDecisions(PolicyData backendPolicy) async {
    await _ensureInitialized();
    
    if (!kDebugMode) return;

    print('\n🔍 POLICY ANALYSIS (Native Enforcement via Socket/FCM):');
    print('Backend Policy: $backendPolicy');
    
    final appliedState = await AppliedPolicyState.getAppliedState();
    print('Applied State: $appliedState');
    
    final decisions = await PolicyDiffEngine.calculateDecisions(backendPolicy);
    
    if (decisions.isEmpty) {
      print('✅ No policy decisions needed - backend and local states match');
    } else {
      print('📋 Policy Decisions (Enforced by Native Side):');
      for (final decision in decisions) {
        final actionIcon = decision.decision.name.startsWith('apply') ? '🔴' : '🟢';
        print('  $actionIcon ${decision.policyName}: ${decision.description}');
      }
    }
    print('⚠️  Enforcement handled by native Android side via socket/FCM');
    print('');
  }

  // Debug method to show current state comparison
  static Future<void> debugPrintStateComparison(PolicyData backendPolicy) async {
    if (!kDebugMode) return;

    await _ensureInitialized();
    final appliedState = await AppliedPolicyState.getAppliedState();
    
    print('\n📊 STATE COMPARISON:');
    print('┌─────────────────────────────────────────────────────┐');
    print('│ POLICY              │ BACKEND    │ APPLIED    │ ACTION    │');
    print('├─────────────────────────────────────────────────────┤');
    print('│ Lock Device         │ ${backendPolicy.lockDevice ? 'true ' : 'false'}│ ${appliedState.lockApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.lockDevice, appliedState.lockApplied)}    │');
    print('│ Camera              │ ${backendPolicy.cameraEnabled ? 'true ' : 'false'}│ ${appliedState.cameraApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.cameraEnabled, appliedState.cameraApplied)}    │');
    print('│ Calls               │ ${backendPolicy.callsEnabled ? 'true ' : 'false'}│ ${appliedState.callsApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.callsEnabled, appliedState.callsApplied)}    │');
    print('│ Social Apps         │ ${backendPolicy.socialAppsEnabled ? 'true ' : 'false'}│ ${appliedState.socialAppsApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.socialAppsEnabled, appliedState.socialAppsApplied)}    │');
    print('│ WiFi                │ ${backendPolicy.wifiEnabled ? 'true ' : 'false'}│ ${appliedState.wifiApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.wifiEnabled, appliedState.wifiApplied)}    │');
    print('│ Mobile Data         │ ${backendPolicy.mobileDataEnabled ? 'true ' : 'false'}│ ${appliedState.mobileDataApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.mobileDataEnabled, appliedState.mobileDataApplied)}    │');
    print('│ USB Debugging       │ ${backendPolicy.usbDebuggingEnabled ? 'true ' : 'false'}│ ${appliedState.usbDebuggingApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.usbDebuggingEnabled, appliedState.usbDebuggingApplied)}    │');
    print('│ Factory Reset       │ ${backendPolicy.factoryResetEnabled ? 'true ' : 'false'}│ ${appliedState.factoryResetApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.factoryResetEnabled, appliedState.factoryResetApplied)}    │');
    print('│ SIM Info            │ ${backendPolicy.getSimInfo ? 'true ' : 'false'}│ ${appliedState.simInfoApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.getSimInfo, appliedState.simInfoApplied)}    │');
    print('│ Location            │ ${backendPolicy.getLocation ? 'true ' : 'false'}│ ${appliedState.locationApplied ? 'true ' : 'false'}│ ${_getActionIcon(backendPolicy.getLocation, appliedState.locationApplied)}    │');
    print('└─────────────────────────────────────────────────────┘');
    print('� Enforcement handled by native Android side via socket/FCM');
    print('');
  }

  static String _getActionIcon(bool backendValue, bool appliedValue) {
    if (!backendValue && !appliedValue) return '🔴'; // Apply
    if (backendValue && appliedValue) return '🟢'; // Unapply
    return '⚪'; // No action
  }
}



