import 'package:flutter/material.dart';
import '../services/connectivity_service.dart';

/// Global banner shown when the device has no internet connection.
/// Listens to [ConnectivityService] and hides automatically when connectivity is restored.
class NoInternetBanner extends StatelessWidget {
  const NoInternetBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final service = ConnectivityService.instance;
    return StreamBuilder<bool>(
      stream: service.connectivityStream,
      initialData: service.isOnline,
      builder: (context, snapshot) {
        final isOnline = snapshot.data ?? true;
        if (isOnline) return const SizedBox.shrink();
        return _BannerContent();
      },
    );
  }
}

class _BannerContent extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    return Material(
      elevation: 2,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        color: colorScheme.errorContainer,
        child: SafeArea(
          bottom: false,
          child: Row(
            children: [
              Icon(
                Icons.wifi_off_rounded,
                color: colorScheme.onErrorContainer,
                size: 22,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'No Internet Connection',
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: colorScheme.onErrorContainer,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
