import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:swatch_health/bridge/health_bridge.dart';

void main() => runApp(const SwatchHealthApp());

class SwatchHealthApp extends StatelessWidget {
  const SwatchHealthApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Swatch Health',
        theme: ThemeData(
          useMaterial3: true,
          colorSchemeSeed: Colors.blue,
          brightness: Brightness.light,
        ),
        darkTheme: ThemeData(
          useMaterial3: true,
          colorSchemeSeed: Colors.blue,
          brightness: Brightness.dark,
        ),
        themeMode: ThemeMode.system,
        home: const HomeScreen(),
      );
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final HealthBridge _bridge = HealthBridge();

  @override
  void initState() {
    super.initState();
    _bridge.start();
  }

  @override
  void dispose() {
    _bridge.dispose();
    super.dispose();
  }

  Future<void> _export() async {
    final csv = _bridge.buildCsv();
    final dir = await getTemporaryDirectory();
    final file = File('${dir.path}/swatch_health_export.csv');
    await file.writeAsString(csv);
    await SharePlus.instance.share(
      ShareParams(
        files: [XFile(file.path)],
        subject: 'Swatch Health export',
        text: 'Swatch Health export',
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: const Text('Swatch Health'),
          actions: [
            IconButton(
              icon: const Icon(Icons.share),
              tooltip: '导出到健康',
              onPressed: _export,
            ),
          ],
        ),
        body: ListenableBuilder(
          listenable: _bridge,
          builder: (context, _) => ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _statusCard(),
              const SizedBox(height: 16),
              _metricsGrid(),
            ],
          ),
        ),
      );

  Widget _statusCard() => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    _bridge.connected
                        ? Icons.bluetooth_connected
                        : Icons.bluetooth,
                    color: _bridge.connected ? Colors.green : Colors.grey,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _bridge.connected
                          ? 'Connected to watch'
                          : 'Not connected',
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.bold),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                _bridge.status,
                style: TextStyle(color: Colors.grey[600], fontSize: 13),
              ),
              const SizedBox(height: 4),
              Row(
                children: [
                  Icon(
                    _bridge.connected ? Icons.check_circle : Icons.circle_outlined,
                    size: 16,
                    color: _bridge.connected ? Colors.green : Colors.grey,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    _bridge.connected
                        ? 'Syncing (export to Health via Shortcut)'
                        : 'Not connected',
                    style: const TextStyle(fontSize: 13),
                  ),
                ],
              ),
            ],
          ),
        ),
      );

  Widget _metricsGrid() => GridView.count(
        crossAxisCount: 2,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 2.4,
        children: [
          _tile('♥', 'Heart Rate', _bridge.heartRate != null ? '${_bridge.heartRate} bpm' : '—'),
          _tile('🫁', 'SpO₂', _bridge.spo2 != null ? '${_bridge.spo2}%' : '—'),
          _tile('👟', 'Steps', _bridge.steps?.toString() ?? '—'),
          _tile('🔥', 'Active Cal', _bridge.calories != null ? '${_bridge.calories} kcal' : '—'),
          _tile('📏', 'Distance', _bridge.distance != null ? '${(_bridge.distance! / 1000.0).toStringAsFixed(2)} km' : '—'),
          _tile('⏱', 'Workout', _bridge.exerciseMinutes != null ? '${_bridge.exerciseCalories} kcal / ${_bridge.exerciseMinutes} min' : '—'),
        ],
      );

  Widget _tile(String icon, String label, String value) => Card(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text('$icon  $label',
                  style: const TextStyle(fontSize: 12, color: Colors.grey)),
              const SizedBox(height: 4),
              Text(value,
                  style: const TextStyle(
                      fontSize: 18, fontWeight: FontWeight.w600)),
            ],
          ),
        ),
      );
}
