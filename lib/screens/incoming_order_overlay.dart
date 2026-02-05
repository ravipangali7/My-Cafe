import 'dart:io';
import 'package:flutter/material.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:vibration/vibration.dart';
import 'package:webview_flutter/webview_flutter.dart';
import '../services/incoming_order_state.dart';

/// Full-screen incoming order UI. Customer and order cards; slide-to-answer bar (center): drag right = Accept.
/// Starts ringtone and vibration in initState.
class IncomingOrderOverlay extends StatefulWidget {
  final IncomingOrderPayload payload;
  final VoidCallback onDismiss;
  final WebViewController? webViewController;

  const IncomingOrderOverlay({
    super.key,
    required this.payload,
    required this.onDismiss,
    this.webViewController,
  });

  @override
  State<IncomingOrderOverlay> createState() => _IncomingOrderOverlayState();
}

class _IncomingOrderOverlayState extends State<IncomingOrderOverlay> {
  final AudioPlayer _player = AudioPlayer();
  bool _isProcessing = false;
  double _slideOffset = 0;

  static const double _slideThreshold = 80;

  @override
  void initState() {
    super.initState();
    _startRingtone();
    _startVibration();
  }

  Future<void> _startRingtone() async {
    try {
      await _player.setReleaseMode(ReleaseMode.loop);
      await _player.setSource(AssetSource('sounds/order_alert.mp3'));
      await _player.resume();
    } catch (e) {
      print('[IncomingOrder] Ringtone not available: $e');
    }
  }

  Future<void> _startVibration() async {
    if (!Platform.isAndroid && !Platform.isIOS) return;
    try {
      final hasVibrator = await Vibration.hasVibrator();
      if (hasVibrator == true) {
        Vibration.vibrate(pattern: [0, 500, 500, 500], repeat: 0);
      }
    } catch (e) {
      print('[IncomingOrder] Vibration error: $e');
    }
  }

  Future<void> _stopMedia() async {
    try {
      await _player.stop();
      try {
        Vibration.cancel();
      } catch (_) {}
    } catch (e) {
      print('[IncomingOrder] Stop media error: $e');
    }
  }

  @override
  void dispose() {
    _stopMedia();
    _player.dispose();
    super.dispose();
  }

  Future<void> _accept() async {
    if (_isProcessing) return;
    setState(() => _isProcessing = true);
    await _stopMedia();
    IncomingOrderState.dismiss();
    widget.onDismiss();
    if (!mounted) return;
    final ctrl = widget.webViewController;
    if (ctrl != null) {
      try {
        final orderId = widget.payload.orderId
            .replaceAll("'", "\\'")
            .replaceAll(r'\', r'\\');
        await ctrl.runJavaScript(
          "window.handleIncomingOrderAction && window.handleIncomingOrderAction('$orderId', 'accepted');",
        );
      } catch (e) {
        print('[IncomingOrder] handleIncomingOrderAction error: $e');
      }
    }
  }

  Future<void> _reject() async {
    if (_isProcessing) return;
    setState(() => _isProcessing = true);
    await _stopMedia();
    IncomingOrderState.dismiss();
    widget.onDismiss();
    if (!mounted) return;
    final ctrl = widget.webViewController;
    if (ctrl != null) {
      try {
        final orderId = widget.payload.orderId
            .replaceAll("'", "\\'")
            .replaceAll(r'\', r'\\');
        await ctrl.runJavaScript(
          "window.handleIncomingOrderAction && window.handleIncomingOrderAction('$orderId', 'rejected');",
        );
      } catch (e) {
        print('[IncomingOrder] handleIncomingOrderAction error: $e');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final payload = widget.payload;

    return Material(
      child: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFF3D2314), Color(0xFF2A1810)],
          ),
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 12),
                // Incoming order header
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      Icons.restaurant_menu,
                      color: Colors.white.withOpacity(0.9),
                      size: 28,
                    ),
                    const SizedBox(width: 10),
                    Text(
                      'Incoming order',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w600,
                        color: Colors.white.withOpacity(0.95),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 20),
                // Customer card (full card design)
                Card(
                  elevation: 4,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  color: const Color(0xFF4A3328),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Customer',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.bold,
                            color: Colors.white.withOpacity(0.7),
                            letterSpacing: 0.5,
                          ),
                        ),
                        const SizedBox(height: 12),
                        _DetailRow(
                          label: 'Name',
                          value: payload.name.isNotEmpty ? payload.name : '—',
                        ),
                        const SizedBox(height: 8),
                        _DetailRow(
                          label: 'Phone',
                          value: payload.phone.isNotEmpty ? payload.phone : '—',
                        ),
                        const SizedBox(height: 8),
                        _DetailRow(
                          label: 'Table',
                          value: payload.tableNo.isNotEmpty
                              ? payload.tableNo
                              : '—',
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                // Order details card (full card design)
                Card(
                  elevation: 4,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  color: const Color(0xFF4A3328),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Order #${payload.orderId}',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.bold,
                            color: Colors.white.withOpacity(0.7),
                            letterSpacing: 0.5,
                          ),
                        ),
                        const SizedBox(height: 12),
                        if (payload.items != null && payload.items!.isNotEmpty)
                          ...payload.items!.map(
                            (item) => Padding(
                              padding: const EdgeInsets.only(bottom: 10),
                              child: Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Expanded(
                                    child: Text(
                                      '${item.productName} (${item.variantName})',
                                      style: const TextStyle(
                                        fontSize: 15,
                                        color: Colors.white,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                  ),
                                  Text(
                                    '${item.quantity} × ₹${item.price} = ₹${item.total}',
                                    style: TextStyle(
                                      fontSize: 13,
                                      color: Colors.white.withOpacity(0.85),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          )
                        else
                          Text(
                            '${payload.itemsCount} item(s)',
                            style: TextStyle(
                              fontSize: 15,
                              color: Colors.white.withOpacity(0.9),
                            ),
                          ),
                        const SizedBox(height: 12),
                        const Divider(color: Colors.white24, height: 1),
                        const SizedBox(height: 12),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              'Total',
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                                color: Colors.white.withOpacity(0.9),
                              ),
                            ),
                            Text(
                              '₹${payload.total}',
                              style: const TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 28),
                // Slide to answer bar (centered in middle of scroll)
                Center(
                  child: Text(
                    'Slide right to accept',
                    style: TextStyle(
                      fontSize: 13,
                      color: Colors.white.withOpacity(0.6),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                _SlideToAnswerBar(
                  slideOffset: _slideOffset,
                  onSlideUpdate: (delta) {
                    setState(() {
                      _slideOffset += delta;
                      _slideOffset = _slideOffset.clamp(0.0, 200.0);
                    });
                  },
                  onSlideEnd: () {
                    if (_slideOffset >= _slideThreshold) {
                      _accept();
                    } else {
                      setState(() => _slideOffset = 0);
                    }
                  },
                ),
                const SizedBox(height: 16),
                Center(
                  child: TextButton(
                    onPressed: _isProcessing ? null : _reject,
                    child: Text(
                      'Reject',
                      style: TextStyle(
                        color: Colors.white.withOpacity(0.8),
                        fontSize: 15,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 24),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  final String label;
  final String value;

  const _DetailRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      children: [
        SizedBox(
          width: 56,
          child: Text(
            '$label:',
            style: TextStyle(
              fontSize: 13,
              color: Colors.white.withOpacity(0.65),
            ),
          ),
        ),
        Expanded(
          child: Text(
            value,
            style: const TextStyle(
              fontSize: 15,
              color: Colors.white,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      ],
    );
  }
}

class _SlideToAnswerBar extends StatelessWidget {
  final double slideOffset;
  final void Function(double delta) onSlideUpdate;
  final VoidCallback onSlideEnd;

  const _SlideToAnswerBar({
    required this.slideOffset,
    required this.onSlideUpdate,
    required this.onSlideEnd,
  });

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        const pillSize = 56.0;
        final barWidth = constraints.maxWidth;
        final maxSlide = barWidth - pillSize - 24;
        final left = 12.0 + slideOffset.clamp(0.0, maxSlide);

        return GestureDetector(
          onHorizontalDragUpdate: (details) => onSlideUpdate(details.delta.dx),
          onHorizontalDragEnd: (_) => onSlideEnd(),
          child: SizedBox(
            height: 64,
            child: Stack(
              alignment: Alignment.centerLeft,
              children: [
                Container(
                  width: barWidth,
                  height: 56,
                  decoration: BoxDecoration(
                    color: const Color(0xFF5C3D2E),
                    borderRadius: BorderRadius.circular(28),
                  ),
                  alignment: Alignment.center,
                  child: Text(
                    'slide to answer',
                    style: TextStyle(
                      fontSize: 16,
                      color: Colors.white.withOpacity(0.6),
                    ),
                  ),
                ),
                Positioned(
                  left: left,
                  child: Container(
                    width: pillSize,
                    height: pillSize,
                    decoration: BoxDecoration(
                      color: const Color(0xFFD4813B),
                      shape: BoxShape.circle,
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black.withOpacity(0.3),
                          blurRadius: 8,
                          offset: const Offset(0, 2),
                        ),
                      ],
                    ),
                    child: const Icon(
                      Icons.arrow_forward,
                      color: Colors.white,
                      size: 28,
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
