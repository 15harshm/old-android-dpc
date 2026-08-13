import 'package:flutter/foundation.dart';
import '../data/policy_data.dart';
import '../../../core/applied_policy_state.dart';

// STEP 3: Decision Types (No Enforcement Yet)
enum PolicyDecision {
  applyLock,
  unapplyLock,
  applyCamera,
  unapplyCamera,
  applyCalls,
  unapplyCalls,
  applySocialApps,
  unapplySocialApps,
  applyWifi,
  unapplyWifi,
  applyMobileData,
  unapplyMobileData,
  applyUsbDebugging,
  unapplyUsbDebugging,
  applyFactoryReset,
  unapplyFactoryReset,
  applySimInfo,
  unapplySimInfo,
  applyLocation,
  unapplyLocation,
  none,
}

class PolicyDecisionResult {
  final PolicyDecision decision;
  final String policyName;
  final String description;

  PolicyDecisionResult({required this.decision, required this.policyName, required this.description});
}

class PolicyDiffEngine {
  // STEP 3: Implement Diff Logic (Decision Only)
  static Future<List<PolicyDecisionResult>> calculateDecisions(PolicyData backendPolicy) async {
    final decisions = <PolicyDecisionResult>[];
    final appliedState = await AppliedPolicyState.getAppliedState();

    // Lock Device Decision Logic
    if (backendPolicy.lockDevice && !appliedState.lockApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applyLock,
        policyName: 'lock_device',
        description: 'APPLY Lock Device (backend: true, local: false)',
      ));
    } else if (!backendPolicy.lockDevice && appliedState.lockApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplyLock,
        policyName: 'lock_device',
        description: 'UNAPPLY Lock Device (backend: false, local: true)',
      ));
    }

    // Camera Decision Logic
    if (backendPolicy.cameraEnabled && !appliedState.cameraApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applyCamera,
        policyName: 'camera',
        description: 'APPLY Camera restriction (backend: true, local: false)',
      ));
    } else if (!backendPolicy.cameraEnabled && appliedState.cameraApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplyCamera,
        policyName: 'camera',
        description: 'UNAPPLY Camera restriction (backend: false, local: true)',
      ));
    }

    // Calls Decision Logic
    if (backendPolicy.callsEnabled && !appliedState.callsApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applyCalls,
        policyName: 'calls',
        description: 'APPLY Calls restriction (backend: true, local: false)',
      ));
    } else if (!backendPolicy.callsEnabled && appliedState.callsApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplyCalls,
        policyName: 'calls',
        description: 'UNAPPLY Calls restriction (backend: false, local: true)',
      ));
    }

    // Social Apps Decision Logic
    if (backendPolicy.socialAppsEnabled && !appliedState.socialAppsApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applySocialApps,
        policyName: 'social_apps',
        description: 'APPLY Social Apps restriction (backend: true, local: false)',
      ));
    } else if (!backendPolicy.socialAppsEnabled && appliedState.socialAppsApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplySocialApps,
        policyName: 'social_apps',
        description: 'UNAPPLY Social Apps restriction (backend: false, local: true)',
      ));
    }

    // WiFi Decision Logic
    if (backendPolicy.wifiEnabled && !appliedState.wifiApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applyWifi,
        policyName: 'wifi',
        description: 'APPLY WiFi restriction (backend: true, local: false)',
      ));
    } else if (!backendPolicy.wifiEnabled && appliedState.wifiApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplyWifi,
        policyName: 'wifi',
        description: 'UNAPPLY WiFi restriction (backend: false, local: true)',
      ));
    }

    // Mobile Data Decision Logic
    if (backendPolicy.mobileDataEnabled && !appliedState.mobileDataApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applyMobileData,
        policyName: 'mobile_data',
        description: 'APPLY Mobile Data restriction (backend: true, local: false)',
      ));
    } else if (!backendPolicy.mobileDataEnabled && appliedState.mobileDataApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplyMobileData,
        policyName: 'mobile_data',
        description: 'UNAPPLY Mobile Data restriction (backend: false, local: true)',
      ));
    }

    // USB Debugging Decision Logic
    if (backendPolicy.usbDebuggingEnabled && !appliedState.usbDebuggingApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applyUsbDebugging,
        policyName: 'usb_debugging',
        description: 'APPLY USB Debugging restriction (backend: true, local: false)',
      ));
    } else if (!backendPolicy.usbDebuggingEnabled && appliedState.usbDebuggingApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplyUsbDebugging,
        policyName: 'usb_debugging',
        description: 'UNAPPLY USB Debugging restriction (backend: false, local: true)',
      ));
    }

    // Factory Reset Decision Logic
    if (backendPolicy.factoryResetEnabled && !appliedState.factoryResetApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applyFactoryReset,
        policyName: 'factory_reset',
        description: 'APPLY Factory Reset restriction (backend: true, local: false)',
      ));
    } else if (!backendPolicy.factoryResetEnabled && appliedState.factoryResetApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplyFactoryReset,
        policyName: 'factory_reset',
        description: 'UNAPPLY Factory Reset restriction (backend: false, local: true)',
      ));
    }

    // SIM Info Decision Logic
    if (!backendPolicy.getSimInfo && !appliedState.simInfoApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applySimInfo,
        policyName: 'sim_info',
        description: 'APPLY SIM Info collection (backend: true, local: false)',
      ));
    } else if (backendPolicy.getSimInfo && appliedState.simInfoApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplySimInfo,
        policyName: 'sim_info',
        description: 'UNAPPLY SIM Info collection (backend: false, local: true)',
      ));
    }

    // Location Decision Logic
    if (backendPolicy.getLocation && !appliedState.locationApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.applyLocation,
        policyName: 'location',
        description: 'APPLY Location collection (backend: true, local: false)',
      ));
    } else if (!backendPolicy.getLocation && appliedState.locationApplied) {
      decisions.add(PolicyDecisionResult(
        decision: PolicyDecision.unapplyLocation,
        policyName: 'location',
        description: 'UNAPPLY Location collection (backend: false, local: true)',
      ));
    }

    return decisions;
  }

  // STEP 4: Integrate Diff Logic with 1-Minute Fetch
  static Future<void> analyzeAndLogDecisions(PolicyData backendPolicy) async {
    if (!kDebugMode) return;

    print('\n🔍 POLICY DECISION ANALYSIS (Phase 2 - Decision Only):');
    print('Backend Policy: $backendPolicy');
    
    final appliedState = await AppliedPolicyState.getAppliedState();
    print('Applied State: $appliedState');
    
    final decisions = await calculateDecisions(backendPolicy);
    
    if (decisions.isEmpty) {
      print('✅ No policy decisions needed - backend and local states match');
    } else {
      print('📋 Policy Decisions (NO ENFORCEMENT YET):');
      for (final decision in decisions) {
        final actionIcon = decision.decision.name.startsWith('apply') ? '🔴' : '🟢';
        print('  $actionIcon ${decision.policyName}: ${decision.description}');
      }
    }
    print('⚠️  DECISIONS ONLY - NO ACTUAL ENFORCEMENT YET');
    print('');
  }

  // Debug method to show current state comparison
  static Future<void> debugPrintStateComparison(PolicyData backendPolicy) async {
    if (!kDebugMode) return;

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
    print('');
  }

  static String _getActionIcon(bool backendValue, bool appliedValue) {
    if (!backendValue && !appliedValue) return '🔴'; // Apply
    if (backendValue && appliedValue) return '🟢'; // Unapply
    return '⚪'; // No action
  }
}



