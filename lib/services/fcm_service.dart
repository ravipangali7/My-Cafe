import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:async';
import 'incoming_order_state.dart';

/// Service for managing Firebase Cloud Messaging (FCM)
class FCMService {
  static final FCMService _instance = FCMService._internal();
  factory FCMService() => _instance;
  FCMService._internal();

  FirebaseMessaging? _messaging;
  String? _currentToken;
  static const String _tokenKey = 'fcm_token';

  /// Initialize Firebase and FCM
  Future<void> initialize() async {
    try {
      // Initialize Firebase if not already initialized
      if (Firebase.apps.isEmpty) {
        await Firebase.initializeApp();
      }

      // Initialize Firebase Messaging
      _messaging = FirebaseMessaging.instance;

      // Request notification permissions
      await _requestPermissions();

      // Get FCM token
      await getToken();

      // Listen for token refresh
      _messaging!.onTokenRefresh.listen((newToken) {
        _currentToken = newToken;
        _saveToken(newToken);
        print('FCM token refreshed: $newToken');
      });

      // Handle foreground messages (incoming order / dismiss)
      FirebaseMessaging.onMessage.listen(_handleMessage);

      // Handle background messages (when app is in background)
      FirebaseMessaging.onBackgroundMessage(
        _firebaseMessagingBackgroundHandler,
      );

      print('FCM Service initialized successfully');
    } catch (e) {
      print('Error initializing FCM Service: $e');
      rethrow;
    }
  }

  /// Request notification permissions
  Future<void> _requestPermissions() async {
    try {
      NotificationSettings settings = await _messaging!.requestPermission(
        alert: true,
        announcement: false,
        badge: true,
        carPlay: false,
        criticalAlert: false,
        provisional: false,
        sound: true,
      );

      print('Notification permission status: ${settings.authorizationStatus}');
    } catch (e) {
      print('Error requesting notification permissions: $e');
    }
  }

  /// Get FCM token with retry logic
  Future<String?> getToken({int maxRetries = 3}) async {
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        print('[FCM] Getting token (attempt $attempt/$maxRetries)...');

        if (_messaging == null) {
          print('[FCM] Messaging not initialized, initializing...');
          await initialize();
        }

        // Try to get cached token first
        print('[FCM] Checking for cached token...');
        final cachedToken = await _getCachedToken();
        if (cachedToken != null && cachedToken.isNotEmpty) {
          _currentToken = cachedToken;
          print(
            '[FCM] Using cached token: ${_currentToken!.substring(0, _currentToken!.length > 20 ? 20 : _currentToken!.length)}...',
          );
          return _currentToken;
        }

        print(
          '[FCM] No cached token found, requesting new token from Firebase...',
        );
        // Get new token from Firebase
        _currentToken = await _messaging!.getToken();

        if (_currentToken != null && _currentToken!.isNotEmpty) {
          await _saveToken(_currentToken!);
          print(
            '[FCM] ✅ FCM token retrieved successfully: ${_currentToken!.substring(0, _currentToken!.length > 30 ? 30 : _currentToken!.length)}...',
          );
          return _currentToken;
        } else {
          print('[FCM] ⚠️ FCM token is null from Firebase');
          if (attempt < maxRetries) {
            print('[FCM] Retrying in ${attempt * 2} seconds...');
            await Future.delayed(Duration(seconds: attempt * 2));
            continue;
          }
        }
      } catch (e) {
        print(
          '[FCM] ❌ Error getting FCM token (attempt $attempt/$maxRetries): $e',
        );
        if (attempt < maxRetries) {
          print('[FCM] Retrying in ${attempt * 2} seconds...');
          await Future.delayed(Duration(seconds: attempt * 2));
        } else {
          print('[FCM] ❌ Failed to get FCM token after $maxRetries attempts');
          return null;
        }
      }
    }

    return _currentToken;
  }

  /// Get current token (cached or fresh)
  String? get currentToken => _currentToken;

  /// Save token to local storage
  Future<void> _saveToken(String token) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_tokenKey, token);
    } catch (e) {
      print('Error saving FCM token: $e');
    }
  }

  /// Get cached token from local storage
  Future<String?> _getCachedToken() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString(_tokenKey);
    } catch (e) {
      print('Error getting cached FCM token: $e');
      return null;
    }
  }

  /// Handle FCM message in foreground.
  /// NOTE: incoming_order is now handled entirely by native Android (IncomingCallActivity).
  /// We only handle dismiss_incoming here to clear any residual state.
  void _handleMessage(RemoteMessage message) {
    final data = message.data;
    if (data.isEmpty) return;
    final type = data['type'] as String?;

    // incoming_order is handled natively by MyCafeFirebaseMessagingService
    // which launches IncomingCallActivity directly

    if (type == 'dismiss_incoming') {
      // Clear any residual state (though overlay is no longer used)
      final orderId = data['order_id'] as String?;
      if (orderId != null) {
        IncomingOrderState.dismissForOrder(orderId);
      } else {
        IncomingOrderState.dismiss();
      }
    }
  }

  /// Call from app after init: check if app was opened from a notification (terminated/killed)
  /// NOTE: incoming_order is now handled entirely by native Android.
  /// This method is kept for any future non-order notification handling.
  Future<void> handleInitialMessage() async {
    try {
      final message = await _messaging?.getInitialMessage();
      if (message != null) {
        final data = message.data;
        // incoming_order is handled by native Android (IncomingCallActivity)
        // No need to show Flutter overlay here
        print('[FCM] Initial message received, type=${data['type']}');
      }
    } catch (e) {
      print('[FCM] handleInitialMessage error: $e');
    }
  }

  /// Delete token (for logout scenarios)
  Future<void> deleteToken() async {
    try {
      if (_messaging != null) {
        await _messaging!.deleteToken();
        _currentToken = null;
        final prefs = await SharedPreferences.getInstance();
        await prefs.remove(_tokenKey);
        print('FCM token deleted');
      }
    } catch (e) {
      print('Error deleting FCM token: $e');
    }
  }
}

/// Background message handler (must be top-level function).
/// On incoming_order we cannot show UI here; app will show overlay when user opens app
/// (via getInitialMessage or when app comes to foreground). Store payload for later if needed.
@pragma('vm:entry-point')
Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  await Firebase.initializeApp();
  final data = message.data;
  if (data.isNotEmpty && data['type'] == 'dismiss_incoming') {
    // Dismiss is handled when app is in foreground via onMessage.
    // When app is in background and user opens it, getInitialMessage may be the tap;
    // if another message was dismiss_incoming, we clear when app resumes - handled by foreground listener.
  }
}
