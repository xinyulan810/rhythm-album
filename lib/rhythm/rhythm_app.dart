import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'rhythm_library_page.dart';

class RhythmApp extends StatelessWidget {
  const new({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '音游相册',
    debugShowCheckedModeBanner: false,
    locale: const Locale('zh', 'CN'),
    supportedLocales: const [Locale('zh', 'CN'), Locale('en')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff5269ce)), useMaterial3: true),
    darkTheme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff9baaff), brightness: Brightness.dark),
      useMaterial3: true,
    ),
    home: const RhythmLibraryPage(),
  );
}
