import 'dart:async';
import 'package:connectivity_plus/connectivity_plus.dart';

/// Exposes real-time connectivity state for the app.
/// Listens to system network changes and exposes [isOnline] and [connectivityStream].
class ConnectivityService {
  ConnectivityService._() {
    _subscription = Connectivity().onConnectivityChanged.listen(_onResult);
    _checkInitial();
  }

  static final ConnectivityService instance = ConnectivityService._();

  final _controller = StreamController<bool>.broadcast();
  StreamSubscription<List<ConnectivityResult>>? _subscription;

  bool _isOnline = true;
  bool get isOnline => _isOnline;

  /// Stream of connectivity updates. Emits true when online, false when offline.
  Stream<bool> get connectivityStream => _controller.stream;

  static bool _resultToOnline(List<ConnectivityResult> results) {
    if (results.isEmpty) return false;
    return results.any((r) => r != ConnectivityResult.none);
  }

  void _onResult(List<ConnectivityResult> results) {
    final online = _resultToOnline(results);
    if (online != _isOnline) {
      _isOnline = online;
      if (!_controller.isClosed) {
        _controller.add(_isOnline);
      }
    }
  }

  Future<void> _checkInitial() async {
    try {
      final results = await Connectivity().checkConnectivity();
      _isOnline = _resultToOnline(results);
      if (!_controller.isClosed) {
        _controller.add(_isOnline);
      }
    } catch (_) {
      _isOnline = false;
      if (!_controller.isClosed) {
        _controller.add(false);
      }
    }
  }

  void dispose() {
    _subscription?.cancel();
    _controller.close();
  }
}
