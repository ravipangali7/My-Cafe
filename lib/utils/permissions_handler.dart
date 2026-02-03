import 'package:permission_handler/permission_handler.dart';

/// Utility class for handling app permissions
class PermissionsHandler {
  /// Request camera permission
  /// Returns true if granted, false otherwise
  static Future<bool> requestCameraPermission() async {
    final status = await Permission.camera.request();
    return status.isGranted;
  }

  /// Request storage permission (for Android < 13)
  /// Returns true if granted, false otherwise
  static Future<bool> requestStoragePermission() async {
    if (await Permission.storage.isGranted) {
      return true;
    }
    final status = await Permission.storage.request();
    return status.isGranted;
  }

  /// Request media images permission (for Android 13+)
  /// Returns true if granted, false otherwise
  static Future<bool> requestMediaImagesPermission() async {
    final status = await Permission.photos.request();
    return status.isGranted;
  }

  /// Request all file-related permissions
  /// Handles both Android < 13 (storage) and Android 13+ (photos)
  static Future<bool> requestFilePermissions() async {
    // Request storage permission for older Android versions
    final storageGranted = await requestStoragePermission();
    
    // Request photos permission for Android 13+
    final photosGranted = await requestMediaImagesPermission();
    
    return storageGranted || photosGranted;
  }

  /// Request notification permission (optional, for future use)
  static Future<bool> requestNotificationPermission() async {
    final status = await Permission.notification.request();
    return status.isGranted;
  }

  /// Check if camera permission is granted
  static Future<bool> isCameraGranted() async {
    return await Permission.camera.isGranted;
  }

  /// Check if storage permission is granted
  static Future<bool> isStorageGranted() async {
    return await Permission.storage.isGranted;
  }

  /// Check if photos permission is granted (Android 13+)
  static Future<bool> isPhotosGranted() async {
    return await Permission.photos.isGranted;
  }
}
