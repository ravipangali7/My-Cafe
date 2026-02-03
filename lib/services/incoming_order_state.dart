import 'dart:convert';

import 'package:flutter/foundation.dart';

/// Single order item line (product name, variant, quantity, price, total).
class IncomingOrderItem {
  final String productName;
  final String variantName;
  final String quantity;
  final String price;
  final String total;

  IncomingOrderItem({
    required this.productName,
    required this.variantName,
    required this.quantity,
    required this.price,
    required this.total,
  });
}

/// Payload for an incoming order from FCM (type=incoming_order).
class IncomingOrderPayload {
  final String orderId;
  final String total;
  final String itemsCount;
  final String name;
  final String tableNo;
  final String phone;
  final List<IncomingOrderItem>? items;

  IncomingOrderPayload({
    required this.orderId,
    required this.total,
    required this.itemsCount,
    required this.name,
    required this.tableNo,
    this.phone = '',
    this.items,
  });

  static IncomingOrderPayload? fromMap(Map<String, dynamic>? data) {
    if (data == null) return null;
    final type = data['type'] as String?;
    if (type != 'incoming_order') return null;
    final orderId = data['order_id'] as String?;
    if (orderId == null || orderId.isEmpty) return null;

    List<IncomingOrderItem>? items;
    final itemsJson = data['items'] as String?;
    if (itemsJson != null && itemsJson.isNotEmpty) {
      try {
        final list = jsonDecode(itemsJson) as List<dynamic>?;
        if (list != null) {
          items = list.map((e) {
            final m = e as Map<String, dynamic>;
            return IncomingOrderItem(
              productName: m['n'] as String? ?? '',
              variantName: m['v'] as String? ?? '',
              quantity: m['q'] as String? ?? '0',
              price: m['p'] as String? ?? '0',
              total: m['t'] as String? ?? '0',
            );
          }).toList();
        }
      } catch (_) {}
    }

    return IncomingOrderPayload(
      orderId: orderId,
      total: data['total'] as String? ?? '0',
      itemsCount: data['items_count'] as String? ?? '0',
      name: data['name'] as String? ?? '',
      tableNo: data['table_no'] as String? ?? '',
      phone: data['phone'] as String? ?? '',
      items: items,
    );
  }
}

/// Global state for the current incoming order alert. FCM sets this; overlay clears on action or dismiss_incoming.
class IncomingOrderState {
  static final ValueNotifier<IncomingOrderPayload?> current =
      ValueNotifier<IncomingOrderPayload?>(null);

  static void show(IncomingOrderPayload payload) {
    current.value = payload;
  }

  static void dismiss() {
    current.value = null;
  }

  static void dismissForOrder(String orderId) {
    final p = current.value;
    if (p != null && p.orderId == orderId) {
      current.value = null;
    }
  }
}
