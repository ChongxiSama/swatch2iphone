import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import 'ble_uuids.dart';

/// A single health sample destined for export (consumed by the Siri Shortcut
/// bridge that writes into Apple Health). Cumulative metrics (steps/calories/
/// distance) are already converted to deltas here so the Shortcut does not
/// double-count when it logs each row.
class HealthSample {
  final String type;
  final double value;
  final String unit;
  final DateTime start;
  final DateTime end;

  HealthSample({
    required this.type,
    required this.value,
    required this.unit,
    required this.start,
    required this.end,
  });
}

/// Bridges the Galaxy Watch (GATT peripheral) and the iPhone.
///
/// This app is BLE central only: it scans for the watch's health service,
/// subscribes to notifications, parses the little-endian readings, and keeps a
/// rolling history. Health data is written into Apple Health by a Siri
/// Shortcut (see export flow) — no HealthKit entitlement / paid account needed.
class HealthBridge extends ChangeNotifier {
  BluetoothAdapterState btState = BluetoothAdapterState.unknown;
  bool connected = false;
  String status = 'Starting…';

  int? heartRate;
  int? spo2;
  int? steps;
  int? calories;
  int? distance;
  int? exerciseCalories;
  int? exerciseMinutes;

  /// Rolling history for CSV export (consumed by the Siri Shortcut bridge).
  final List<HealthSample> history = [];

  // Cumulative-metric delta tracking (the watch sends running daily totals).
  int? _prevSteps;
  int? _prevCalories;
  int? _prevDistance;
  DateTime? _prevStepsTs;
  DateTime? _prevCaloriesTs;
  DateTime? _prevDistanceTs;

  BluetoothDevice? _device;
  StreamSubscription<BluetoothAdapterState>? _adapterSub;
  StreamSubscription<List<ScanResult>>? _scanSub;
  final List<StreamSubscription<List<int>>> _notifySubs = [];

  void start() {
    _initBle();
  }

  void _initBle() {
    FlutterBluePlus.setOptions(restoreState: true);
    _adapterSub = FlutterBluePlus.adapterState.listen((state) {
      btState = state;
      if (state == BluetoothAdapterState.on) {
        _startScan();
      } else {
        connected = false;
        status = switch (state) {
          BluetoothAdapterState.off => 'Bluetooth off',
          BluetoothAdapterState.unauthorized => 'Bluetooth denied',
          _ => 'Bluetooth unavailable',
        };
        notifyListeners();
      }
    });
  }

  void _startScan() {
    if (connected) return;
    status = 'Searching for watch…';
    notifyListeners();
    _scanSub?.cancel();
    _scanSub = FlutterBluePlus.onScanResults.listen((results) {
      for (final r in results) {
        if (r.advertisementData.serviceUuids
            .contains(BleUuids.healthServiceGuid)) {
          _connect(r.device);
          break;
        }
      }
    });
    FlutterBluePlus.startScan(
      withServices: [BleUuids.healthServiceGuid],
      timeout: const Duration(seconds: 30),
    ).catchError((e) {
      status = 'Scan error: $e';
      notifyListeners();
    });
  }

  Future<void> _connect(BluetoothDevice device) async {
    _scanSub?.cancel();
    await FlutterBluePlus.stopScan();
    _device = device;
    try {
      await device.connect(license: License.nonprofit);
      status = 'Connected to ${device.platformName}';
      connected = true;
      notifyListeners();

      final services = await device.discoverServices();
      final svc = services.firstWhere(
        (s) => s.uuid == BleUuids.healthServiceGuid,
        orElse: () => throw Exception('health service not found'),
      );

      for (final c in svc.characteristics) {
        if (BleUuids.isHealthChar(c.uuid)) {
          await c.setNotifyValue(true);
          _notifySubs.add(
            c.onValueReceived.listen((data) => _onValue(c.uuid, data)),
          );
        }
      }
      status = 'Syncing health data…';
      notifyListeners();
    } catch (e) {
      connected = false;
      status = 'Connection failed: $e';
      notifyListeners();
      _retryScan();
    }
  }

  void _retryScan() {
    Future.delayed(const Duration(seconds: 3), () {
      if (!connected) _startScan();
    });
  }

  void _onValue(Guid uuid, List<int> data) {
    final now = DateTime.now();
    if (uuid == Guid(BleUuids.heartRate) && data.isNotEmpty) {
      heartRate = data[0];
      _push(HealthSample(
        type: 'hr',
        value: heartRate!.toDouble(),
        unit: 'bpm',
        start: now,
        end: now,
      ));
    } else if (uuid == Guid(BleUuids.spo2) && data.isNotEmpty) {
      spo2 = data[0];
      _push(HealthSample(
        type: 'spo2',
        value: spo2!.toDouble(),
        unit: '%',
        start: now,
        end: now,
      ));
    } else if (uuid == Guid(BleUuids.steps) && data.length >= 4) {
      final v = _readLe32(data);
      steps = v;
      _pushCumulative(
        'steps',
        'count',
        _prevSteps,
        _prevStepsTs,
        v,
        (p, ts) {
          _prevSteps = p;
          _prevStepsTs = ts;
        },
      );
    } else if (uuid == Guid(BleUuids.calories) && data.length >= 4) {
      final v = _readLe32(data);
      calories = v;
      _pushCumulative(
        'cal',
        'kcal',
        _prevCalories,
        _prevCaloriesTs,
        v,
        (p, ts) {
          _prevCalories = p;
          _prevCaloriesTs = ts;
        },
      );
    } else if (uuid == Guid(BleUuids.distance) && data.length >= 4) {
      final v = _readLe32(data);
      distance = v;
      _pushCumulative(
        'dist',
        'm',
        _prevDistance,
        _prevDistanceTs,
        v,
        (p, ts) {
          _prevDistance = p;
          _prevDistanceTs = ts;
        },
      );
    } else if (uuid == Guid(BleUuids.exercise) && data.length >= 2) {
      exerciseCalories = data[0];
      exerciseMinutes = data[1];
      final end = now;
      final start = end.subtract(Duration(minutes: exerciseMinutes!));
      _push(HealthSample(
        type: 'workout',
        value: exerciseCalories!.toDouble(),
        unit: 'kcal',
        start: start,
        end: end,
      ));
    } else if (uuid == Guid(BleUuids.sleep) && data.length >= 8) {
      final startSec = _readLe32(data.sublist(0, 4));
      final endSec = _readLe32(data.sublist(4, 8));
      final start = DateTime.fromMillisecondsSinceEpoch(startSec * 1000);
      final end = DateTime.fromMillisecondsSinceEpoch(endSec * 1000);
      _push(HealthSample(
        type: 'sleep',
        value: 0,
        unit: '',
        start: start,
        end: end,
      ));
    }
    notifyListeners();
  }

  void _push(HealthSample s) {
    history.add(s);
    if (history.length > 5000) history.removeAt(0);
  }

  void _pushCumulative(
    String type,
    String unit,
    int? prevVal,
    DateTime? prevTs,
    int newVal,
    void Function(int, DateTime) setPrev,
  ) {
    final now = DateTime.now();
    if (prevVal != null &&
        prevTs != null &&
        newVal >= prevVal &&
        now.isAfter(prevTs)) {
      final delta = (newVal - prevVal).toDouble();
      if (delta > 0) {
        _push(HealthSample(type: type, value: delta, unit: unit, start: prevTs, end: now));
      }
    }
    setPrev(newVal, now);
  }

  /// Builds the CSV consumed by the Siri Shortcut bridge.
  /// Columns: type,value,unit,start,end (ISO-8601).
  String buildCsv() {
    final buf = StringBuffer();
    buf.writeln('type,value,unit,start,end');
    for (final s in history) {
      buf.writeln(
        '${s.type},${s.value},${s.unit},${s.start.toIso8601String()},${s.end.toIso8601String()}',
      );
    }
    return buf.toString();
  }

  int _readLe32(List<int> d) {
    return d[0] | (d[1] << 8) | (d[2] << 16) | (d[3] << 24);
  }

  @override
  void dispose() {
    _adapterSub?.cancel();
    _scanSub?.cancel();
    for (final s in _notifySubs) {
      s.cancel();
    }
    _device?.disconnect();
    super.dispose();
  }
}
