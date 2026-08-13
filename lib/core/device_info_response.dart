class DeviceInfoResponse {
  final bool status;
  final UserInfo user;
  final CustomerInfo customer;
  final EmiSummary emiSummary;
  final double penalty;
  final List<EmiDetail> emiList;

  DeviceInfoResponse({
    required this.status,
    required this.user,
    required this.customer,
    required this.emiSummary,
    required this.penalty,
    required this.emiList,
  });

  factory DeviceInfoResponse.fromJson(Map<String, dynamic> json) {
    print('📦 DeviceInfoResponse Received: $json');
    print('💰 Parsed Penalty: ${json['penalty']}');
    
    return DeviceInfoResponse(
      status: json['status'] ?? false,
      user: UserInfo.fromJson(json['user'] ?? {}),
      customer: CustomerInfo.fromJson(json['customer'] ?? {}),
      emiSummary: EmiSummary.fromJson(json['emi_summary'] ?? {}),
      penalty: (json['penalty'] ?? 0).toDouble(),
      emiList: (json['emi_list'] as List?)
              ?.map((e) => EmiDetail.fromJson(e))
              .toList() ??
          [],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'status': status,
      'user': user.toJson(),
      'customer': customer.toJson(),
      'emi_summary': emiSummary.toJson(),
      'penalty': penalty,
      'emi_list': emiList.map((e) => e.toJson()).toList(),
    };
  }
}

class UserInfo {
  final String userName;
  final String phone;
  final String address;

  UserInfo({
    required this.userName,
    required this.phone,
    required this.address,
  });

  factory UserInfo.fromJson(Map<String, dynamic> json) {
    return UserInfo(
      userName: json['user_name'] ?? '',
      phone: json['phone'] ?? '',
      address: json['address'] ?? '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'user_name': userName,
      'phone': phone,
      'address': address,
    };
  }
}

class CustomerInfo {
  final int id;
  final int userId;
  final String customerName;
  final String phoneNumber;
  final String brand;
  final String model;
  final String imei1;
  final String imei2;
  final String? sim1;
  final String? sim2;
  final String devicePrice;
  final String processingFee;
  final String downPayment;
  final String numberOfEmi;
  final String interestRate;
  final String monthlyEmi;
  final String firstEmiDate;
  final int lockDevice;
  final int cameraEnabled;
  final int callsEnabled;
  final int socialAppsEnabled;
  final int wifiEnabled;
  final int mobileDataEnabled;
  final int usbDebuggingEnabled;
  final int offlineLock;
  final int factoryResetEnabled;
  final int getSimInfo;
  final int getLocation;
  final int active;
  final String createdAt;

  CustomerInfo({
    required this.id,
    required this.userId,
    required this.customerName,
    required this.phoneNumber,
    required this.brand,
    required this.model,
    required this.imei1,
    required this.imei2,
    this.sim1,
    this.sim2,
    required this.devicePrice,
    required this.processingFee,
    required this.downPayment,
    required this.numberOfEmi,
    required this.interestRate,
    required this.monthlyEmi,
    required this.firstEmiDate,
    required this.lockDevice,
    required this.cameraEnabled,
    required this.callsEnabled,
    required this.socialAppsEnabled,
    required this.wifiEnabled,
    required this.mobileDataEnabled,
    required this.usbDebuggingEnabled,
    required this.offlineLock,
    required this.factoryResetEnabled,
    required this.getSimInfo,
    required this.getLocation,
    required this.active,
    required this.createdAt,
  });

  factory CustomerInfo.fromJson(Map<String, dynamic> json) {
    return CustomerInfo(
      id: json['id'] ?? 0,
      userId: json['user_id'] ?? 0,
      customerName: json['customer_name'] ?? '',
      phoneNumber: json['phone_number'] ?? '',
      brand: json['brand'] ?? '',
      model: json['model'] ?? '',
      imei1: json['imei1'] ?? '',
      imei2: json['imei2'] ?? '',
      sim1: json['sim_1'],
      sim2: json['sim_2'],
      devicePrice: json['device_price'] ?? '0',
      processingFee: json['processing_fee'] ?? '0',
      downPayment: json['down_payment'] ?? '0',
      numberOfEmi: json['number_of_emi'] ?? '0',
      interestRate: json['interest_rate'] ?? '0',
      monthlyEmi: json['monthly_emi'] ?? '0',
      firstEmiDate: json['first_emi_date'] ?? '',
      lockDevice: json['lock_device'] ?? 0,
      cameraEnabled: json['camera_enabled'] ?? 0,
      callsEnabled: json['calls_enabled'] ?? 0,
      socialAppsEnabled: json['social_apps_enabled'] ?? 0,
      wifiEnabled: json['wifi_enabled'] ?? 0,
      mobileDataEnabled: json['mobile_data_enabled'] ?? 0,
      usbDebuggingEnabled: json['usb_debugging_enabled'] ?? 0,
      offlineLock: json['offline_lock'] ?? 0,
      factoryResetEnabled: json['factory_reset_enabled'] ?? 0,
      getSimInfo: json['get_sim_info'] ?? 0,
      getLocation: json['get_location'] ?? 0,
      active: json['active'] ?? 0,
      createdAt: json['created_at'] ?? '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'user_id': userId,
      'customer_name': customerName,
      'phone_number': phoneNumber,
      'brand': brand,
      'model': model,
      'imei1': imei1,
      'imei2': imei2,
      'sim_1': sim1,
      'sim_2': sim2,
      'device_price': devicePrice,
      'processing_fee': processingFee,
      'down_payment': downPayment,
      'number_of_emi': numberOfEmi,
      'interest_rate': interestRate,
      'monthly_emi': monthlyEmi,
      'first_emi_date': firstEmiDate,
      'lock_device': lockDevice,
      'camera_enabled': cameraEnabled,
      'calls_enabled': callsEnabled,
      'social_apps_enabled': socialAppsEnabled,
      'wifi_enabled': wifiEnabled,
      'mobile_data_enabled': mobileDataEnabled,
      'usb_debugging_enabled': usbDebuggingEnabled,
      'offline_lock': offlineLock,
      'factory_reset_enabled': factoryResetEnabled,
      'get_sim_info': getSimInfo,
      'get_location': getLocation,
      'active': active,
      'created_at': createdAt,
    };
  }
}

class EmiSummary {
  final int totalEmi;
  final String paidEmi;
  final String pendingEmi;
  final double totalAmount;

  EmiSummary({
    required this.totalEmi,
    required this.paidEmi,
    required this.pendingEmi,
    required this.totalAmount,
  });

  factory EmiSummary.fromJson(Map<String, dynamic> json) {
    return EmiSummary(
      totalEmi: json['total_emi'] ?? 0,
      paidEmi: json['paid_emi'] ?? '0',
      pendingEmi: json['pending_emi'] ?? '0',
      totalAmount: (json['total_amount'] ?? 0).toDouble(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'total_emi': totalEmi,
      'paid_emi': paidEmi,
      'pending_emi': pendingEmi,
      'total_amount': totalAmount,
    };
  }
}

class EmiDetail {
  final int id;
  final int emiNo;
  final String dueDate;
  final String amount;
  final int status;
  final String? paidDate;
  final String? mode;

  EmiDetail({
    required this.id,
    required this.emiNo,
    required this.dueDate,
    required this.amount,
    required this.status,
    this.paidDate,
    this.mode,
  });

  factory EmiDetail.fromJson(Map<String, dynamic> json) {
    return EmiDetail(
      id: json['id'] ?? 0,
      emiNo: json['emi_no'] ?? 0,
      dueDate: json['due_date'] ?? '',
      amount: json['amount'] ?? '0',
      status: json['status'] ?? 0,
      paidDate: json['paid_date'],
      mode: json['mode'],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'emi_no': emiNo,
      'due_date': dueDate,
      'amount': amount,
      'status': status,
      'paid_date': paidDate,
      'mode': mode,
    };
  }

  bool get isPaid => status == 1;
  bool isOverdue() {
    if (isPaid) return false;
    try {
      final dueDate = DateTime.parse(this.dueDate);
      return DateTime.now().isAfter(dueDate);
    } catch (e) {
      return false;
    }
  }
}



