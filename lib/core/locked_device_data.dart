class LockedDeviceData {
  final int userId;
  final String name;
  final String phone;
  final String address;
  final String paymentPhoto;

  LockedDeviceData({
    required this.userId,
    required this.name,
    required this.phone,
    required this.address,
    required this.paymentPhoto,
  });

  factory LockedDeviceData.fromJson(Map<String, dynamic> json) {
    return LockedDeviceData(
      userId: json['user_id'] ?? 0,
      name: json['name'] ?? '',
      phone: json['phone'] ?? '',
      address: json['address'] ?? '',
      paymentPhoto: json['payment_photo'] ?? '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'user_id': userId,
      'name': name,
      'phone': phone,
      'address': address,
      'payment_photo': paymentPhoto,
    };
  }

  @override
  String toString() {
    return 'LockedDeviceData(userId: $userId, name: $name, phone: $phone, address: $address, paymentPhoto: $paymentPhoto)';
  }
}



