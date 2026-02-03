import 'package:shared_preferences/shared_preferences.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'dart:convert';

/// Service for managing session persistence and cookies
class SessionService {
  static const String _cookieKey = 'webview_cookies';
  static const String _lastUrlKey = 'last_url';
  static final WebViewCookieManager _cookieManager = WebViewCookieManager();

  /// Save cookies to shared preferences
  /// Uses JavaScript to read cookies from the document
  static Future<void> saveCookies(
    WebViewController controller, {
    String? domain,
  }) async {
    try {
      // Use JavaScript to get cookies from document.cookie
      final result = await controller.runJavaScriptReturningResult(
        'document.cookie',
      );

      if (result == null) {
        return;
      }

      final cookieString = result.toString();
      final prefs = await SharedPreferences.getInstance();

      // Parse cookies from the cookie string
      // Format: "name1=value1; name2=value2; ..."
      final cookiesList = <Map<String, dynamic>>[];

      if (cookieString.isNotEmpty &&
          cookieString != 'null' &&
          cookieString != 'undefined') {
        // Remove quotes if present
        final cleanCookieString = cookieString.replaceAll(RegExp(r'^"|"$'), '');
        final cookiePairs = cleanCookieString.split(';');

        for (final pair in cookiePairs) {
          final trimmed = pair.trim();
          if (trimmed.isNotEmpty) {
            final parts = trimmed.split('=');
            if (parts.length >= 2) {
              final name = parts[0].trim();
              final value = parts.sublist(1).join('=').trim();

              if (name.isNotEmpty && value.isNotEmpty) {
                cookiesList.add({
                  'name': name,
                  'value': value,
                  'domain': domain ?? '',
                  'path': '/',
                });
              }
            }
          }
        }
      }

      if (cookiesList.isNotEmpty) {
        await prefs.setString(_cookieKey, jsonEncode(cookiesList));
      }
    } catch (e) {
      print('Error saving cookies: $e');
    }
  }

  /// Restore cookies from shared preferences
  static Future<void> restoreCookies(
    WebViewController controller, {
    String? domain,
  }) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final cookiesJson = prefs.getString(_cookieKey);

      if (cookiesJson != null && cookiesJson.isNotEmpty) {
        final cookies = jsonDecode(cookiesJson) as List;

        for (final cookieData in cookies) {
          try {
            final name = cookieData['name'] as String?;
            final value = cookieData['value'] as String?;
            final cookieDomain =
                cookieData['domain'] as String? ?? domain ?? '';

            if (name != null &&
                name.isNotEmpty &&
                value != null &&
                value.isNotEmpty) {
              final cookie = WebViewCookie(
                name: name,
                value: value,
                domain: cookieDomain.isNotEmpty ? cookieDomain : (domain ?? ''),
                path: cookieData['path'] as String? ?? '/',
              );

              await _cookieManager.setCookie(cookie);
            }
          } catch (e) {
            print('Error setting cookie ${cookieData['name']}: $e');
          }
        }
      }
    } catch (e) {
      print('Error restoring cookies: $e');
    }
  }

  /// Save last visited URL
  static Future<void> saveLastUrl(String url) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_lastUrlKey, url);
    } catch (e) {
      print('Error saving last URL: $e');
    }
  }

  /// Get last visited URL
  static Future<String?> getLastUrl() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString(_lastUrlKey);
    } catch (e) {
      print('Error getting last URL: $e');
      return null;
    }
  }

  /// Build Cookie header string from stored cookies (for API calls from Flutter)
  static Future<String> getCookieHeader({String? domain}) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final cookiesJson = prefs.getString(_cookieKey);
      if (cookiesJson == null || cookiesJson.isEmpty) return '';
      final cookies = jsonDecode(cookiesJson) as List;
      final parts = <String>[];
      for (final c in cookies) {
        final name = c['name'] as String?;
        final value = c['value'] as String?;
        if (name != null && value != null && name.isNotEmpty) {
          parts.add('$name=$value');
        }
      }
      return parts.join('; ');
    } catch (e) {
      print('Error getting cookie header: $e');
      return '';
    }
  }

  /// Clear all session data
  static Future<void> clearSession() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_cookieKey);
      await prefs.remove(_lastUrlKey);
    } catch (e) {
      print('Error clearing session: $e');
    }
  }
}
