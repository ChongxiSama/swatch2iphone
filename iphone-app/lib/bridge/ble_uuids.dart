import 'package:flutter_blue_plus/flutter_blue_plus.dart';

class BleUuids {
  static const String healthService = 'B2C4E6F8-1A2B-3C4D-5E6F-7A8B9C0D1E2F';
  static const String heartRate = 'B2C4E6F8-1A2B-3C4D-5E6F-7A8B9C0D1E30';
  static const String spo2 = 'B2C4E6F8-1A2B-3C4D-5E6F-7A8B9C0D1E31';
  static const String steps = 'B2C4E6F8-1A2B-3C4D-5E6F-7A8B9C0D1E32';
  static const String calories = 'B2C4E6F8-1A2B-3C4D-5E6F-7A8B9C0D1E33';
  static const String distance = 'B2C4E6F8-1A2B-3C4D-5E6F-7A8B9C0D1E34';
  // Sleep: 8 bytes = startEpochSec (LE u32) + endEpochSec (LE u32), from Health Connect
  static const String sleep = 'B2C4E6F8-1A2B-3C4D-5E6F-7A8B9C0D1E35';
  static const String exercise = 'B2C4E6F8-1A2B-3C4D-5E6F-7A8B9C0D1E36';

  static final Guid healthServiceGuid = Guid(healthService);

  static final List<Guid> characteristics = [
    Guid(heartRate),
    Guid(spo2),
    Guid(steps),
    Guid(calories),
    Guid(distance),
    Guid(sleep),
    Guid(exercise),
  ];

  static bool isHealthChar(Guid uuid) => characteristics.contains(uuid);
}
