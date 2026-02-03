import 'dart:convert';
import 'package:http/http.dart' as http;
import '../config/app_config.dart';

/// Service for making API calls to Django backend
class ApiService {
  static final ApiService _instance = ApiService._internal();
  factory ApiService() => _instance;
  ApiService._internal();

  /// Base URL for the API (same domain as dashboard)
  String get baseUrl => AppConfig.dashboardUrl;

  /// Send FCM token with phone number to Django
  ///
  /// [phone] - User's phone number
  /// [fcmToken] - FCM device token
  ///
  /// Returns true if successful, false otherwise
  Future<bool> sendFCMTokenByPhone(String phone, String fcmToken) async {
    try {
      final url = Uri.parse('$baseUrl/api/fcm-token-by-phone/');
      print('[API] Sending POST request to: $url');
      print(
        '[API] Payload: {phone: $phone, fcm_token: ${fcmToken.substring(0, fcmToken.length > 30 ? 30 : fcmToken.length)}...}',
      );

      final response = await http
          .post(
            url,
            headers: {'Content-Type': 'application/json'},
            body: jsonEncode({'phone': phone, 'fcm_token': fcmToken}),
          )
          .timeout(
            const Duration(seconds: 10),
            onTimeout: () {
              print('[API] ❌ Request timeout after 10 seconds');
              throw Exception('Request timeout');
            },
          );

      print('[API] Response status: ${response.statusCode}');
      print('[API] Response body: ${response.body}');

      if (response.statusCode == 200 || response.statusCode == 201) {
        final responseData = jsonDecode(response.body);
        print(
          '[API] ✅ FCM token saved successfully: ${responseData['message']}',
        );
        if (responseData.containsKey('token_id')) {
          print('[API] Token ID: ${responseData['token_id']}');
        }
        return true;
      } else {
        print(
          '[API] ❌ Failed to save FCM token: ${response.statusCode} - ${response.body}',
        );
        return false;
      }
    } catch (e) {
      print('[API] ❌ Error sending FCM token to Django: $e');
      return false;
    }
  }

  /// Retry sending FCM token with exponential backoff
  Future<bool> sendFCMTokenByPhoneWithRetry(
    String phone,
    String fcmToken, {
    int maxRetries = 3,
  }) async {
    print('[API Retry] Starting retry logic (max $maxRetries attempts)');

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
      print('[API Retry] Attempt $attempt/$maxRetries');
      final success = await sendFCMTokenByPhone(phone, fcmToken);
      if (success) {
        print('[API Retry] ✅ Success on attempt $attempt');
        return true;
      }

      if (attempt < maxRetries) {
        // Exponential backoff: 1s, 2s, 4s
        final delay = Duration(seconds: 1 << (attempt - 1));
        print(
          '[API Retry] ⏳ Retrying in ${delay.inSeconds}s (attempt $attempt/$maxRetries)',
        );
        await Future.delayed(delay);
      }
    }

    print('[API Retry] ❌ Failed after $maxRetries attempts');
    return false;
  }

  /// Update order status (accept/reject). Uses session cookies for auth.
  Future<bool> updateOrderStatus(
    String orderId,
    String status, {
    String? rejectReason,
    String? cookieHeader,
  }) async {
    try {
      final url = Uri.parse('$baseUrl/api/orders/$orderId/edit/');
      final body = <String, String>{'status': status};
      if (rejectReason != null && rejectReason.isNotEmpty) {
        body['reject_reason'] = rejectReason;
      }
      final headers = <String, String>{
        'Content-Type': 'application/x-www-form-urlencoded',
      };
      if (cookieHeader != null && cookieHeader.isNotEmpty) {
        headers['Cookie'] = cookieHeader;
      }
      final response = await http
          .post(
            url,
            headers: headers,
            body: body.entries
                .map(
                  (e) =>
                      '${Uri.encodeComponent(e.key)}=${Uri.encodeComponent(e.value)}',
                )
                .join('&'),
          )
          .timeout(const Duration(seconds: 15));
      if (response.statusCode == 200) return true;
      print(
        '[API] updateOrderStatus failed: ${response.statusCode} ${response.body}',
      );
      return false;
    } catch (e) {
      print('[API] updateOrderStatus error: $e');
      return false;
    }
  }
}
