import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'config/app_config.dart';
import 'screens/no_internet_screen.dart';
import 'screens/webview_screen.dart';
import 'services/connectivity_service.dart';
import 'services/fcm_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  // Set preferred orientations
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);

  // Defer FCM init so first frame (splash) paints immediately
  runApp(const MyApp());

  // Initialize FCM in background after first frame
  WidgetsBinding.instance.addPostFrameCallback((_) {
    FCMService().initialize().catchError((e) {
      print('Warning: FCM initialization failed: $e');
    });
  });
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: AppConfig.appName,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF3D2314),
          primary: const Color(0xFFD4813B),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      home: const WebViewScreen(),
      builder: (context, child) {
        final service = ConnectivityService.instance;
        return StreamBuilder<bool>(
          stream: service.connectivityStream,
          initialData: service.isOnline,
          builder: (context, snapshot) {
            final isOnline = snapshot.data ?? true;
            if (isOnline && child != null) return child;
            return const NoInternetScreen();
          },
        );
      },
    );
  }
}
