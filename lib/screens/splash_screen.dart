import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../config/app_config.dart';
import 'webview_screen.dart';

/// Splash screen displayed when the app starts
class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> {
  @override
  void initState() {
    super.initState();
    _navigateToWebView();
  }

  void _navigateToWebView() {
    Future.delayed(Duration(seconds: AppConfig.splashDurationSeconds), () {
      if (mounted) {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (context) => const WebViewScreen()),
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    // Set system UI overlay style for full-screen experience
    SystemChrome.setSystemUIOverlayStyle(
      const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.dark,
      ),
    );

    return Scaffold(
      backgroundColor: const Color(0xFFFFF8F0),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // App Logo/Icon - You can replace this with an actual logo asset
            Container(
              width: 120,
              height: 120,
              decoration: BoxDecoration(
                color: const Color(0xFF3D2314),
                borderRadius: BorderRadius.circular(20),
              ),
              child: const Icon(
                Icons.local_cafe,
                size: 60,
                color: Color(0xFFFFF8F0),
              ),
            ),
            const SizedBox(height: 24),
            // App Name
            const Text(
              'My Cafe',
              style: TextStyle(
                fontSize: 32,
                fontWeight: FontWeight.bold,
                color: Color(0xFF3D2314),
                letterSpacing: 1.2,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'MY CAFE',
              style: TextStyle(
                fontSize: 16,
                color: Color(0xFF5C3D2E),
                letterSpacing: 0.5,
              ),
            ),
            const SizedBox(height: 48),
            // Loading indicator
            const CircularProgressIndicator(
              valueColor: AlwaysStoppedAnimation<Color>(Color(0xFFD4813B)),
            ),
          ],
        ),
      ),
    );
  }
}
