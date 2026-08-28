// This is a basic Flutter widget test.
import 'package:flutter_test/flutter_test.dart';

import 'package:swatch_health/main.dart';

void main() {
  testWidgets('App bar shows the app title', (WidgetTester tester) async {
    await tester.pumpWidget(const SwatchHealthApp());
    expect(find.text('Swatch Health'), findsOneWidget);
  });
}
