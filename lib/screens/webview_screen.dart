import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:open_filex/open_filex.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:file_picker/file_picker.dart';
import 'package:http/http.dart' as http;
import '../config/app_config.dart';
import '../services/session_service.dart';
import '../services/fcm_service.dart';
import '../utils/permissions_handler.dart';
// NOTE: Incoming order: notification tap opens app; Flutter shows React order-alert page in WebView.

/// Main WebView screen that displays the admin dashboard
class WebViewScreen extends StatefulWidget {
  const WebViewScreen({super.key});

  @override
  State<WebViewScreen> createState() => _WebViewScreenState();
}

class _WebViewScreenState extends State<WebViewScreen>
    with WidgetsBindingObserver {
  late final WebViewController _controller;
  final FCMService _fcmService = FCMService();
  bool _isLoading = true;
  bool _fcmTokenSent =
      false; // Track if FCM token has been sent for current session

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initializeWebView();
    FCMService().handleInitialMessage();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkPendingIncomingOrderFromPlatform();
    }
  }

  /// When app was opened from notification (background/killed), Android passes payload via method channel.
  /// Handles different types:
  /// - accept/reject action: call handleIncomingOrderAction to update order status
  /// - order_rejected: navigate to order detail page (after reject action from native UI)
  /// - incoming_order with navigate_to: navigate to order detail page (after accept from native UI)
  /// NOTE: Flutter overlay is no longer used - native Android IncomingCallActivity handles the UI.
  Future<void> _checkPendingIncomingOrderFromPlatform() async {
    if (!mounted) return;
    try {
      const channel = MethodChannel('incoming_order');
      final result = await channel.invokeMethod('getPendingIncomingOrder');
      if (result is Map && result.isNotEmpty) {
        final data = Map<String, dynamic>.from(
          result.map((k, v) => MapEntry(k.toString(), v)),
        );
        final type = data['type'] as String?;
        final action = data['action'] as String?;
        final navigateTo = data['navigate_to'] as String?;
        final orderId = data['order_id'] as String?;

        // Handle order_rejected type - navigate to order detail
        if (type == 'order_rejected' && orderId != null) {
          final escaped = orderId
              .replaceAll("'", "\\'")
              .replaceAll(r'\', r'\\');
          await _controller.runJavaScript(
            "window.openOrderDetail && window.openOrderDetail('$escaped');",
          );
          return;
        }

        // Handle accept/reject action from notification
        if (action == 'accept' || action == 'reject') {
          if (orderId != null) {
            final status = action == 'accept' ? 'accepted' : 'rejected';
            final escaped = orderId
                .replaceAll("'", "\\'")
                .replaceAll(r'\', r'\\');
            await _controller.runJavaScript(
              "window.handleIncomingOrderAction && window.handleIncomingOrderAction('$escaped', '$status');",
            );

            // If navigate_to is set, also navigate to order detail
            if (navigateTo == 'order_detail') {
              // Wait a bit for the action to be processed, then navigate
              await Future.delayed(const Duration(milliseconds: 500));
              await _controller.runJavaScript(
                "window.openOrderDetail && window.openOrderDetail('$escaped');",
              );
            }
          }
          return;
        }

        // Handle incoming_order with navigate_to (from accept action)
        if (type == 'incoming_order' &&
            navigateTo == 'order_detail' &&
            orderId != null) {
          final escaped = orderId
              .replaceAll("'", "\\'")
              .replaceAll(r'\', r'\\');
          await _controller.runJavaScript(
            "window.openOrderDetail && window.openOrderDetail('$escaped');",
          );
          return;
        }

        // Incoming order (no action): open React order-alert page and inject payload
        if (type == 'incoming_order' &&
            orderId != null &&
            action != 'accept' &&
            action != 'reject') {
          final baseUrl = AppConfig.dashboardUrl;
          final orderAlertUrl =
              '$baseUrl/order-alert?orderId=${Uri.encodeComponent(orderId)}';
          final payload = <String, dynamic>{
            'order_id': orderId,
            'name': data['name'] ?? '',
            'table_no': data['table_no'] ?? '',
            'phone': data['phone'] ?? '',
            'total': data['total'] ?? '',
            'items_count': data['items_count'] ?? '',
            'items': data['items'] ?? '',
          };
          final payloadJson = jsonEncode(payload);
          final payloadEscaped = payloadJson
              .replaceAll(r'\', r'\\')
              .replaceAll('"', r'\"')
              .replaceAll('\n', r'\n')
              .replaceAll('\r', r'\r');
          await _controller.runJavaScript(
            'window.__INCOMING_ORDER__ = JSON.parse("$payloadEscaped"); window.location.href = "$orderAlertUrl";',
          );
        }
      }
    } catch (e) {
      print('[WebView] getPendingIncomingOrder error: $e');
    }
  }

  /// Opens the React order-alert page with the given payload (from native when app is in foreground).
  Future<void> _openOrderAlertWithPayload(Map<String, dynamic> data) async {
    if (!mounted) return;
    try {
      final orderId = data['order_id'] as String?;
      if (orderId == null || orderId.isEmpty) return;
      final baseUrl = AppConfig.dashboardUrl;
      final orderAlertUrl =
          '$baseUrl/order-alert?orderId=${Uri.encodeComponent(orderId)}';
      final payload = <String, dynamic>{
        'order_id': orderId,
        'name': data['name'] ?? '',
        'table_no': data['table_no'] ?? '',
        'phone': data['phone'] ?? '',
        'total': data['total'] ?? '',
        'items_count': data['items_count'] ?? '',
        'items': data['items'] ?? '',
      };
      final payloadJson = jsonEncode(payload);
      final payloadEscaped = payloadJson
          .replaceAll(r'\', r'\\')
          .replaceAll('"', r'\"')
          .replaceAll('\n', r'\n')
          .replaceAll('\r', r'\r');
      await _controller.runJavaScript(
        'window.__INCOMING_ORDER__ = JSON.parse("$payloadEscaped"); window.location.href = "$orderAlertUrl";',
      );
    } catch (e) {
      print('[WebView] _openOrderAlertWithPayload error: $e');
    }
  }

  Future<void> _initializeWebView() async {
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.white)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (String url) {
            setState(() {
              _isLoading = true;
            });
            SessionService.saveLastUrl(url);

            // Inject Notification API polyfill early, before page content loads
            // This ensures it's available when Firebase initializes
            try {
              _controller.runJavaScript('''
                (function() {
                  // Early Notification API polyfill for webview
                  if (typeof window !== 'undefined' && typeof Notification === 'undefined') {
                    console.log('[WebView] Injecting Notification API polyfill early...');
                    window.Notification = function(title, options) {
                      console.log('[WebView] Notification created:', title);
                      return {
                        title: title,
                        body: options?.body || '',
                        icon: options?.icon || '',
                        tag: options?.tag || '',
                        data: options?.data || {},
                        close: function() { console.log('[WebView] Notification closed'); }
                      };
                    };
                    window.Notification.permission = 'default';
                    window.Notification.requestPermission = function() {
                      return new Promise(function(resolve) {
                        if (navigator.permissions && navigator.permissions.query) {
                          navigator.permissions.query({name: 'notifications'}).then(function(result) {
                            window.Notification.permission = result.state === 'granted' ? 'granted' : 
                                                             result.state === 'denied' ? 'denied' : 'default';
                            resolve(window.Notification.permission);
                          }).catch(function() { resolve('default'); });
                        } else {
                          resolve('default');
                        }
                      });
                    };
                    window.Notification.maxActions = 0;
                  }
                })();
              ''');
            } catch (e) {
              print('Error injecting early Notification polyfill: $e');
            }
          },
          onPageFinished: (String url) async {
            setState(() {
              _isLoading = false;
            });
            // Save cookies after page loads
            try {
              final uri = Uri.parse(url);
              await SessionService.saveCookies(_controller, domain: uri.host);
            } catch (e) {
              print('Error saving cookies on page finished: $e');
            }

            // Check if dashboard page is loaded and register FCM token
            // Check for both base URL (after login redirects) and /dashboard path
            final isDashboardPage =
                url.contains('mycafe.sewabyapar.com') &&
                (url.contains('/dashboard') ||
                    url.endsWith('mycafe.sewabyapar.com') ||
                    url.endsWith('mycafe.sewabyapar.com/'));

            if (isDashboardPage && !_fcmTokenSent) {
              print('[WebView] Dashboard page detected: $url');
              // Wait a bit for React to fully load before registering
              Future.delayed(const Duration(seconds: 3), () {
                if (mounted && !_fcmTokenSent) {
                  _registerFCMToken();
                }
              });
            }

            // Run pending order check when dashboard is ready (cold start from notification)
            if (isDashboardPage) {
              _checkPendingIncomingOrderFromPlatform();
            }

            // Disable text selection and copy in WebView
            try {
              await _controller.runJavaScript('''
                (function() {
                  function disableSelection() {
                    var style = document.createElement('style');
                    style.textContent = 'body, body *, html { -webkit-user-select: none !important; -moz-user-select: none !important; -ms-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; }';
                    (document.head || document.documentElement).appendChild(style);
                    document.body && (document.body.style.webkitUserSelect = 'none');
                    document.body && (document.body.style.userSelect = 'none');
                    document.documentElement.style.webkitUserSelect = 'none';
                    document.documentElement.style.userSelect = 'none';
                  }
                  if (document.body) {
                    disableSelection();
                  } else {
                    document.addEventListener('DOMContentLoaded', disableSelection);
                  }
                  document.addEventListener('copy', function(e) { e.preventDefault(); }, false);
                  document.addEventListener('cut', function(e) { e.preventDefault(); }, false);
                })();
              ''');
            } catch (e) {
              print('Error disabling WebView selection: $e');
            }

            // Inject JavaScript polyfills and configuration for FCM support
            // This ensures Notification API and service workers work properly in the webview
            try {
              await _controller.runJavaScript('''
                (function() {
                  window.__FLUTTER_WEBVIEW__ = true;
                  // Ensure service worker API is available
                  if ('serviceWorker' in navigator) {
                    console.log('[WebView] Service Worker API is available');
                    
                    // Force enable service workers if needed
                    if (navigator.serviceWorker && !navigator.serviceWorker.controller) {
                      console.log('[WebView] Service Worker controller not yet available, will be ready when registered');
                    }
                  } else {
                    console.warn('[WebView] Service Worker API is not available');
                  }
                  
                  // Ensure localStorage and sessionStorage are available (for DOM storage)
                  try {
                    localStorage.setItem('_webview_check', '1');
                    localStorage.removeItem('_webview_check');
                    console.log('[WebView] localStorage is available');
                  } catch (e) {
                    console.warn('[WebView] localStorage may not be available:', e);
                  }
                  
                  // Notification API polyfill for webview
                  // Some Android WebViews don't expose Notification API properly
                  if (typeof window !== 'undefined') {
                    // Check if Notification API exists
                    if (typeof Notification === 'undefined' || Notification.permission === undefined) {
                      console.warn('[WebView] Notification API not available, attempting polyfill');
                      
                      // Create a minimal Notification polyfill
                      if (typeof Notification === 'undefined') {
                        window.Notification = function(title, options) {
                          console.log('[WebView] Notification polyfill called:', title);
                          // Return a minimal notification object
                          return {
                            title: title,
                            body: options?.body || '',
                            icon: options?.icon || '',
                            tag: options?.tag || '',
                            data: options?.data || {},
                            close: function() {
                              console.log('[WebView] Notification closed');
                            }
                          };
                        };
                        
                        // Set static properties
                        window.Notification.permission = 'default';
                        window.Notification.requestPermission = function() {
                          return new Promise(function(resolve) {
                            console.log('[WebView] Notification permission requested');
                            // Try to request permission if available
                            if (navigator.permissions && navigator.permissions.query) {
                              navigator.permissions.query({name: 'notifications'}).then(function(result) {
                                window.Notification.permission = result.state === 'granted' ? 'granted' : 
                                                                 result.state === 'denied' ? 'denied' : 'default';
                                resolve(window.Notification.permission);
                              }).catch(function() {
                                resolve('default');
                              });
                            } else {
                              // Fallback: assume permission can be requested
                              window.Notification.permission = 'default';
                              resolve('default');
                            }
                          });
                        };
                        
                        // Set maxActions to 0 for compatibility
                        window.Notification.maxActions = 0;
                      }
                    } else {
                      console.log('[WebView] Notification API is available');
                      
                      // Ensure requestPermission returns a Promise
                      if (typeof Notification.requestPermission !== 'function' || 
                          !Notification.requestPermission().then) {
                        const originalRequestPermission = Notification.requestPermission;
                        Notification.requestPermission = function() {
                          return new Promise(function(resolve) {
                            if (typeof originalRequestPermission === 'function') {
                              const result = originalRequestPermission();
                              if (result instanceof Promise) {
                                result.then(resolve).catch(function() { resolve('default'); });
                              } else {
                                resolve(result || 'default');
                              }
                            } else {
                              resolve('default');
                            }
                          });
                        };
                      }
                    }
                    
                    // Ensure PushManager is available (required for FCM)
                    if ('serviceWorker' in navigator && navigator.serviceWorker.ready) {
                      navigator.serviceWorker.ready.then(function(registration) {
                        if (registration.pushManager) {
                          console.log('[WebView] PushManager is available');
                        } else {
                          console.warn('[WebView] PushManager may not be available');
                        }
                      }).catch(function(e) {
                        console.warn('[WebView] Error checking PushManager:', e);
                      });
                    }
                  }
                })();
              ''');
            } catch (e) {
              print('Error injecting FCM support script: $e');
            }
          },
          onWebResourceError: (WebResourceError error) {
            setState(() {
              _isLoading = false;
            });
          },
          onNavigationRequest: (NavigationRequest request) async {
            final uri = Uri.tryParse(request.url);
            if (uri != null) {
              // Handle phone calls - intercept tel: scheme
              if (uri.scheme == 'tel') {
                await launchUrl(uri);
                return NavigationDecision.prevent;
              }
              // Handle WhatsApp links - open in WhatsApp app
              if (uri.scheme == 'https' &&
                  (uri.host == 'wa.me' || uri.host == 'api.whatsapp.com')) {
                await launchUrl(uri, mode: LaunchMode.externalApplication);
                return NavigationDecision.prevent;
              }
              // Handle whatsapp: scheme directly
              if (uri.scheme == 'whatsapp') {
                await launchUrl(uri, mode: LaunchMode.externalApplication);
                return NavigationDecision.prevent;
              }
            }
            // Keep all other navigation within the app
            return NavigationDecision.navigate;
          },
        ),
      )
      ..addJavaScriptChannel(
        'FileUpload',
        onMessageReceived: (JavaScriptMessage message) {
          _handleFileUploadRequest(message.message);
        },
      )
      ..addJavaScriptChannel(
        'SendFCMToken',
        onMessageReceived: (JavaScriptMessage message) {
          // React can acknowledge receipt if needed
          print(
            '[FCM Channel] React acknowledged FCM token receipt: ${message.message}',
          );
        },
      )
      ..addJavaScriptChannel(
        'RequestFCMTokenForLogout',
        onMessageReceived: (JavaScriptMessage message) async {
          await _sendFCMTokenForLogoutToReact();
        },
      )
      ..addJavaScriptChannel(
        'ExitApp',
        onMessageReceived: (JavaScriptMessage message) {
          if (mounted) {
            _showExitDialog();
          }
        },
      )
      ..addJavaScriptChannel(
        'RequestExit',
        onMessageReceived: (JavaScriptMessage message) {
          if (mounted) {
            _showExitDialog();
          }
        },
      )
      ..addJavaScriptChannel(
        'SaveFile',
        onMessageReceived: (JavaScriptMessage message) {
          _handleSaveFile(message.message);
        },
      )
      ..addJavaScriptChannel(
        'OpenInBrowser',
        onMessageReceived: (JavaScriptMessage message) {
          _openUrlInBrowser(message.message);
        },
      )
      ..addJavaScriptChannel(
        'StopOrderAlertSound',
        onMessageReceived: (JavaScriptMessage message) async {
          try {
            const channel = MethodChannel('incoming_order');
            await channel.invokeMethod<void>('stopOrderAlertSound');
          } catch (e) {
            print('[WebView] stopOrderAlertSound error: $e');
          }
        },
      );

    // When app is in foreground, native invokes onIncomingOrder so we open order-alert immediately
    if (Platform.isAndroid) {
      const incomingChannel = MethodChannel('incoming_order');
      incomingChannel.setMethodCallHandler((MethodCall call) async {
        if (call.method == 'onIncomingOrder' && call.arguments != null) {
          await _openOrderAlertWithPayload(
            Map<String, dynamic>.from(call.arguments as Map),
          );
        }
        return null;
      });
    }

    _controller
      ..setUserAgent(
        'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 (WebView)',
      );

    // Restore cookies before loading
    final dashboardUri = Uri.parse(AppConfig.dashboardUrl);
    await SessionService.restoreCookies(_controller, domain: dashboardUri.host);

    // Load the dashboard URL
    await _controller.loadRequest(dashboardUri);

    // Disable zoom and configure webview settings
    if (Platform.isAndroid) {
      _controller.enableZoom(false);
    }
  }

  /// Register FCM token when dashboard page loads
  /// Flow: 1. Get FCM token, 2. Send token to React, 3. React makes API call
  Future<void> _registerFCMToken() async {
    try {
      print('[FCM Registration] Starting FCM token registration process...');

      // Step 1: Get FCM token (with retry logic)
      print('[FCM Registration] Step 1: Getting FCM token...');
      final fcmToken = await _fcmService.getToken(maxRetries: 3);
      if (fcmToken == null || fcmToken.isEmpty) {
        print(
          '[FCM Registration] ❌ FCM token is null or empty, cannot register',
        );
        return;
      }

      print('[FCM Registration] ✅ Step 1 complete: FCM token retrieved');

      // Step 2: Wait for React to be ready
      print('[FCM Registration] Step 2: Waiting for React to be ready...');
      await Future.delayed(const Duration(seconds: 3));

      // Step 3: Send FCM token to React (React will make API call)
      print('[FCM Registration] Step 3: Sending FCM token to React...');
      await _sendFCMTokenToReact(fcmToken);
    } catch (e) {
      print('[FCM Registration] ❌ Error in registration process: $e');
    }
  }

  /// Send FCM token to React
  /// React will receive the token and make the API call to Django
  Future<void> _sendFCMTokenToReact(String fcmToken) async {
    try {
      print('[FCM Send] Sending FCM token to React...');

      // Method 1: Set window object and call function
      await _controller.runJavaScript('''
        (function() {
          try {
            // Set token in window object for direct access
            window.__FLUTTER_FCM_TOKEN__ = '$fcmToken';
            console.log('[Flutter] FCM token set in window.__FLUTTER_FCM_TOKEN__');
            
            // Call React function if available
            if (window.receiveFCMTokenFromFlutter && typeof window.receiveFCMTokenFromFlutter === 'function') {
              console.log('[Flutter] Calling receiveFCMTokenFromFlutter function');
              window.receiveFCMTokenFromFlutter('$fcmToken');
            } else {
              console.warn('[Flutter] receiveFCMTokenFromFlutter function not available yet, token is in window.__FLUTTER_FCM_TOKEN__');
            }
          } catch (e) {
            console.error('[Flutter] Error sending FCM token to React:', e);
          }
        })();
      ''');

      print(
        '[FCM Send] ✅ FCM token sent to React via window object and function call',
      );

      // Mark as sent to prevent duplicates
      if (mounted) {
        setState(() {
          _fcmTokenSent = true;
        });
      }
    } catch (e) {
      print('[FCM Send] ❌ Error sending FCM token to React: $e');
    }
  }

  /// Send FCM token to React for logout flow. React requests token via RequestFCMTokenForLogout
  /// channel; we get the token and call window.__onFCMTokenForLogout__(token).
  Future<void> _sendFCMTokenForLogoutToReact() async {
    try {
      final fcmToken = await _fcmService.getToken(maxRetries: 1);
      final String js;
      if (fcmToken != null && fcmToken.isNotEmpty) {
        final escaped = fcmToken
            .replaceAll(r'\', r'\\')
            .replaceAll("'", r"\'")
            .replaceAll('\n', r'\n')
            .replaceAll('\r', r'\r');
        js =
            "window.__onFCMTokenForLogout__ && window.__onFCMTokenForLogout__('$escaped');";
      } else {
        js =
            "window.__onFCMTokenForLogout__ && window.__onFCMTokenForLogout__(null);";
      }
      await _controller.runJavaScript(js);
    } catch (e) {
      print('[WebView] RequestFCMTokenForLogout error: $e');
      await _controller.runJavaScript(
        'window.__onFCMTokenForLogout__ && window.__onFCMTokenForLogout__(null);',
      );
    }
  }

  /// Parse accept string (e.g. "image/*" or "image/*,application/pdf") into file extensions for FilePicker.
  List<String> _acceptToExtensions(String? accept) {
    if (accept == null || accept.isEmpty) {
      return ['jpg', 'jpeg', 'png', 'gif', 'webp'];
    }
    final parts = accept.split(',').map((s) => s.trim().toLowerCase()).toList();
    final extensions = <String>[];
    for (final p in parts) {
      if (p == 'image/*') {
        extensions.addAll(['jpg', 'jpeg', 'png', 'gif', 'webp']);
      } else if (p == 'application/pdf') {
        extensions.add('pdf');
      }
    }
    if (extensions.isEmpty) {
      return ['jpg', 'jpeg', 'png', 'gif', 'webp'];
    }
    return extensions.toSet().toList();
  }

  static String _mimeFromExtension(String? extension) {
    if (extension == null || extension.isEmpty)
      return 'application/octet-stream';
    final ext = extension.toLowerCase();
    const map = {
      'png': 'image/png',
      'jpg': 'image/jpeg',
      'jpeg': 'image/jpeg',
      'gif': 'image/gif',
      'webp': 'image/webp',
      'pdf': 'application/pdf',
    };
    return map[ext] ?? 'application/octet-stream';
  }

  Future<void> _handleFileUploadRequest(String message) async {
    // WebView flow: Web sends JSON { accept, field }. Pick file, return file data (base64 data URL) to React; React shows preview and uploads on Submit.
    try {
      final Map<String, dynamic>? options =
          jsonDecode(message) as Map<String, dynamic>?;
      final accept = options?['accept'] as String? ?? 'image/*';
      final field = options?['field'] as String? ?? 'logo';

      await PermissionsHandler.requestFilePermissions();
      final extensions = _acceptToExtensions(accept);
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: extensions,
        allowMultiple: false,
      );
      if (result == null ||
          result.files.isEmpty ||
          result.files.single.path == null) {
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('No file selected')));
        }
        return;
      }
      final filePath = result.files.single.path!;
      final fileName = result.files.single.name;
      final extension = result.files.single.extension;
      final mimeType = _mimeFromExtension(extension);
      final bytes = await File(filePath).readAsBytes();
      final base64 = base64Encode(bytes);
      final dataUrl = 'data:$mimeType;base64,$base64';
      final payload = <String, String>{
        'dataUrl': dataUrl,
        'fileName': fileName,
        'mimeType': mimeType,
      };
      final jsonStr = jsonEncode(payload);
      await _controller.runJavaScript(
        "window.__fileUploadCallback && window.__fileUploadCallback($jsonStr);",
      );
    } catch (e) {
      print('FileUpload error: $e');
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('File pick failed: $e')));
      }
    }
  }

  /// Open URL in external browser (e.g. Chrome). Message is the URL string.
  Future<void> _openUrlInBrowser(String urlString) async {
    try {
      final uri = Uri.tryParse(urlString);
      if (uri == null || !uri.hasScheme) return;
      final launched = await launchUrl(
        uri,
        mode: LaunchMode.externalApplication,
      );
      if (!launched && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Could not open in browser')),
        );
      }
    } catch (e) {
      print('OpenInBrowser error: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Could not open in browser')),
        );
      }
    }
  }

  /// Save file from WebView (e.g. QR PNG/PDF). Message is JSON: { base64, filename, mimeType }
  /// or { dataUrl, filename, mimeType } where dataUrl is data:image/png;base64,... or similar.
  /// Saves to Downloads when available so file appears in file manager; shows "Open file" action.
  Future<void> _handleSaveFile(String message) async {
    try {
      if (Platform.isAndroid) {
        final granted = await PermissionsHandler.requestFilePermissions();
        if (!granted && mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Storage permission is needed to save the file'),
            ),
          );
          return;
        }
      }

      final map = jsonDecode(message) as Map<String, dynamic>?;
      if (map == null) return;
      final filename = map['filename'] as String? ?? 'download';
      String? base64Data = map['base64'] as String?;
      final dataUrl = map['dataUrl'] as String?;
      if (base64Data == null && dataUrl != null) {
        // Strip data:...;base64, prefix
        final comma = dataUrl.indexOf(',');
        base64Data = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
      }
      if (base64Data == null || base64Data.isEmpty) return;
      final bytes = base64Decode(base64Data);

      // Prefer Downloads (user-visible on many devices); fallback to app documents.
      // On Android 10+ getDownloadsDirectory() may be app-specific; "Open" action still opens the file.
      Directory? dir = await getDownloadsDirectory();
      if (dir == null || !await dir.exists()) {
        dir = await getApplicationDocumentsDirectory();
      }
      if (!await dir.exists()) {
        await dir.create(recursive: true);
      }
      final file = File('${dir.path}/$filename');
      await file.writeAsBytes(bytes);
      final savedPath = file.path;

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('File saved: $filename'),
            action: SnackBarAction(
              label: 'Open',
              onPressed: () async {
                final result = await OpenFilex.open(savedPath);
                if (mounted && result.type != ResultType.done) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Could not open file')),
                  );
                }
              },
            ),
          ),
        );
      }
    } catch (e) {
      print('SaveFile channel error: $e');
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('Failed to save file')));
      }
    }
  }

  Future<void> _onRefresh() async {
    await _controller.reload();
  }

  /// Asks the web app to handle back (in-app history). Web calls RequestExit when at root.
  Future<void> _handleBackButton() async {
    await _controller.runJavaScript(
      'window.__handleFlutterBack && window.__handleFlutterBack();',
    );
  }

  /// Shows exit confirmation dialog. Exit closes app; Cancel dismisses dialog.
  Future<void> _showExitDialog() async {
    if (!mounted) return;
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final screenWidth = MediaQuery.of(context).size.width;
    final dialogWidth = (screenWidth * 0.85).clamp(280.0, 340.0);
    final shouldExit = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (context) => Center(
        child: SizedBox(
          width: dialogWidth,
          child: AlertDialog(
            title: const Text('Exit app?'),
            content: const Text('Are you sure you want to exit?'),
            contentPadding: const EdgeInsets.fromLTRB(24, 20, 24, 0),
            actionsPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: Text(
                  'Cancel',
                  style: TextStyle(color: colorScheme.primary),
                ),
              ),
              FilledButton(
                onPressed: () => Navigator.of(context).pop(true),
                style: FilledButton.styleFrom(
                  backgroundColor: colorScheme.error,
                  foregroundColor: colorScheme.onError,
                ),
                child: const Text('Exit'),
              ),
            ],
          ),
        ),
      ),
    );
    if (shouldExit == true) {
      SystemNavigator.pop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (bool didPop, dynamic result) async {
        if (!didPop) {
          await _handleBackButton();
        }
      },
      child: Scaffold(
        body: SafeArea(
          child: RefreshIndicator(
            onRefresh: _onRefresh,
            child: LayoutBuilder(
              builder: (context, constraints) {
                final viewportHeight = constraints.maxHeight;
                final viewportWidth = constraints.maxWidth;
                return SingleChildScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  child: SizedBox(
                    height: viewportHeight,
                    width: viewportWidth,
                    child: Stack(
                      children: [
                        Positioned.fill(
                          child: WebViewWidget(controller: _controller),
                        ),
                        if (_isLoading)
                          Positioned.fill(
                            child: Container(
                              color: Colors.white,
                              child: const Center(
                                child: CircularProgressIndicator(
                                  valueColor: AlwaysStoppedAnimation<Color>(
                                    Colors.brown,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        // NOTE: IncomingOrderOverlay removed - native Android IncomingCallActivity handles incoming order UI
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }
}
