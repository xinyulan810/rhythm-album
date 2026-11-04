import 'package:aves/app_flavor.dart';
import 'package:aves/rhythm/rhythm_app.dart';
import 'package:equatable/equatable.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:leak_tracker/leak_tracker.dart';
import 'package:material_ui/material_ui.dart';

void mainCommon(AppFlavor flavor, {Map<String, Object?>? debugIntentData}) {
  debugProfilePlatformChannels = false;

  // HttpClient.enableTimelineLogging = true; // enable network traffic logging
  // debugPrintGestureArenaDiagnostics = true;

  EquatableConfig.stringify = true;
  // The screenshot app owns its channel and services. Do not retain the
  // upstream gallery's media/database/video service graph in this entry point.

  // Errors during the widget build phase will show by default:
  // - in debug mode: error on red background
  // - in profile/release mode: plain grey background
  // This can be modified via `ErrorWidget.builder`
  // ErrorWidget.builder = (details) => ErrorWidget(details.exception);
  // cf https://docs.flutter.dev/testing/errors

  WidgetsFlutterBinding.ensureInitialized();
  LicenseRegistry.addLicense(() async* {
    yield LicenseEntryWithLineBreaks(['Aves'], await rootBundle.loadString('LICENSE'));
    for (final path in ['LICENSE', 'PATENTS']) {
      yield LicenseEntryWithLineBreaks(['libaom'], await rootBundle.loadString('experiments/vendor/aom/$path'));
    }
  });
  PaintingBinding.instance.imageCache
    ..maximumSizeBytes = 48 * 1024 * 1024
    ..maximumSize = 160;
  if (kDebugMode) {
    LeakTracking.start();
    FlutterMemoryAllocations.instance.addListener(
      (event) => LeakTracking.dispatchObjectEvent(event.toMap()),
    );
  }
  runApp(const RhythmApp());
}
