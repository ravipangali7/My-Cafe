import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:image_picker/image_picker.dart';
import 'package:webview_flutter/webview_flutter.dart';
import '../utils/permissions_handler.dart';

/// Service class for handling WebView file uploads and interactions
class WebViewService {
  final ImagePicker _imagePicker = ImagePicker();
  
  /// Handle file upload request from WebView
  /// Supports both camera capture and gallery selection
  Future<FilePickerResult?> handleFileUpload({
    required WebViewController controller,
    bool allowMultiple = false,
    List<String>? allowedExtensions,
  }) async {
    try {
      // Request necessary permissions
      await PermissionsHandler.requestFilePermissions();
      
      // Show file picker
      final result = await FilePicker.platform.pickFiles(
        type: FileType.any,
        allowMultiple: allowMultiple,
        allowedExtensions: allowedExtensions,
      );
      
      return result;
    } catch (e) {
      print('Error picking file: $e');
      return null;
    }
  }

  /// Handle image capture from camera
  Future<XFile?> captureImageFromCamera() async {
    try {
      // Request camera permission
      final hasPermission = await PermissionsHandler.requestCameraPermission();
      if (!hasPermission) {
        return null;
      }

      final image = await _imagePicker.pickImage(
        source: ImageSource.camera,
        imageQuality: 85,
        maxWidth: 2048,
        maxHeight: 2048,
      );

      return image;
    } catch (e) {
      print('Error capturing image: $e');
      return null;
    }
  }

  /// Handle image selection from gallery
  Future<XFile?> pickImageFromGallery() async {
    try {
      // Request file permissions
      await PermissionsHandler.requestFilePermissions();

      final image = await _imagePicker.pickImage(
        source: ImageSource.gallery,
        imageQuality: 85,
        maxWidth: 2048,
        maxHeight: 2048,
      );

      return image;
    } catch (e) {
      print('Error picking image: $e');
      return null;
    }
  }

  /// Convert XFile to File for WebView upload
  File? xFileToFile(XFile? xFile) {
    if (xFile == null) return null;
    return File(xFile.path);
  }
}
