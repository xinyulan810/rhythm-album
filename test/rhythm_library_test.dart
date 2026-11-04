import 'dart:async';

import 'package:aves/rhythm/rhythm_app.dart';
import 'package:aves/rhythm/rhythm_gallery_controller.dart';
import 'package:aves/rhythm/rhythm_library_page.dart';
import 'package:aves/rhythm/rhythm_viewer.dart';
import 'package:aves/rhythm/rhythm_motion.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aves_magnifier/aves_magnifier.dart';

RhythmPhoto photo(int index) => {
  'id': 'photo-$index',
  'name': 'Screenshot_$index.png',
  'game': 'Test',
  'song': '',
  'album': '',
  'tags': '',
  'thumbnail': '',
  'status': 'ready',
  'favorite': 0,
  'trashed': 0,
  'taken': 1700000000000,
  'created': 1700000000000,
  'width': 1080,
  'height': 2400,
  'originalBytes': 1000,
  'storedBytes': 500,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = RhythmGalleryController.channel;
  final calls = <MethodCall>[];
  var rows = <RhythmPhoto>[];
  var access = 'none';
  final stats = <String, Object?>{};

  setUp(() {
    calls.clear();
    rows = [];
    access = 'none';
    stats.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      final args = call.arguments as Map? ?? {};
      if (call.method == 'list') {
        final offset = args['offset'] as int? ?? 0;
        return {
          'items': rows.skip(offset).take(args['limit'] as int? ?? 60).toList(),
          'total': rows.length,
          'stats': {
            'count': rows.length,
            'pending': 0,
            'albums': ['练习'],
            'mediaAccess': access,
            'autoImport': false,
            'budget': {'prefetch': 0, 'decodeJobs': 1, 'cacheMb': 24},
            ...stats,
          },
        };
      }
      if (call.method == 'view') throw PlatformException(code: 'pending', message: '测试解码失败');
      if (call.method == 'selectionIds') return rows.map((r) => r['id']).toList();
      if (call.method == 'enableAuto') {
        access = 'partial';
        stats['autoImport'] = true;
        return access;
      }
      return null;
    });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  testWidgets('first launch requires explicit managed screenshot choice', (tester) async {
    stats['managedChoiceMade'] = false;
    stats['managedSupported'] = true;
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    expect(find.text('是否代管截图？'), findsOneWidget);
    expect(calls.where((c) => c.method == 'deleteManagedSources'), isEmpty);
    stats['managedChoiceMade'] = true;
    await tester.tap(find.text('关闭，保留原图'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'managedMode').single.arguments, {'enabled': false});
    expect(calls.where((c) => c.method == 'deleteManagedSources'), isEmpty);
    await tester.pumpWidget(const SizedBox());
  });

  test('delayed frame reports use frame timestamps rather than current route', () {
    final phases = RhythmFramePhases();
    phases.mark('viewer_open', timestamp: 100);
    phases.mark('viewer_idle', timestamp: 300);
    phases.mark('viewer_return', timestamp: 500);
    expect(phases.at(90), 'startup');
    expect(phases.at(150), 'viewer_open');
    expect(phases.at(300), 'viewer_idle');
    expect(phases.at(520), 'viewer_return');
  });

  testWidgets('fit decode uses physical pixels and zoom keeps original resolution', (tester) async {
    final item = {...photo(0), 'width': 2160, 'height': 4800};
    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(size: Size(360, 800), devicePixelRatio: 3),
        child: Builder(
          builder: (context) {
            final fitted = rhythmDisplayImage(context, item, 'unused') as ResizeImage;
            expect(fitted.width, 1080);
            expect(fitted.height, isNull);
            expect(rhythmDisplayImage(context, item, 'unused', original: true), isA<FileImage>());
            expect(rhythmDisplayImage(context, {...item, 'width': 360, 'height': 800}, 'unused'), isA<FileImage>());
            return const SizedBox();
          },
        ),
      ),
    );
  });

  testWidgets('surface switch does not duplicate scroll controllers or Heroes', (tester) async {
    final controller = ScrollController();
    Widget surface(String identity) => MaterialApp(
      home: RhythmSurfaceTransition(
        identity: identity,
        child: ListView(
          controller: controller,
          children: const [Hero(tag: 'photo', child: Text('photo'))],
        ),
      ),
    );
    await tester.pumpWidget(surface('albums'));
    await tester.pumpWidget(surface('collection'));
    await tester.pump(const Duration(milliseconds: 40));
    expect(controller.positions.length, 1);
    expect(find.byType(Hero), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpAndSettle();
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('opening and cancelled downward drag retain the full image layer', (tester) async {
    rows = List.generate(3, photo);
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    await tester.tap(find.bySemanticsLabel('Screenshot_0.png'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 40));
    expect(tester.widgetList<RhythmImagePage>(find.byType(RhythmImagePage)).every((page) => page.fullResolution), isTrue);
    await tester.pumpAndSettle();
    final page = find.byType(RhythmImagePage).first;
    final element = page.evaluate().single;
    final drag = await tester.startGesture(const Offset(400, 200));
    await drag.moveBy(const Offset(0, 65));
    await tester.pump(const Duration(milliseconds: 40));
    expect(tester.widget<RhythmImagePage>(page).fullResolution, isTrue);
    expect(page.evaluate().single, same(element));
    await tester.pump(const Duration(milliseconds: 700));
    await drag.up();
    await tester.pumpAndSettle();
    expect(find.byType(RhythmViewer), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('screenshot collection is the home, automatic access is explicit', (tester) async {
    await tester.pumpWidget(const RhythmApp());
    await tester.pumpAndSettle();
    expect(find.text('音游相册'), findsOneWidget);
    expect(find.byType(Drawer), findsNothing);
    expect(find.byTooltip('手动导入'), findsOneWidget);
    await tester.tap(find.text('开启'));
    await tester.pumpAndSettle();
    expect(calls.where((e) => e.method == 'enableAuto').single.arguments, {'enabled': true});
    expect(calls.any((e) => e.method == 'scan'), isTrue);
    expect(find.text('仅能自动收纳已授权的照片'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('search and album filter use latest first with selection replacing sort', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Glorious Crown');
    await tester.pump(const Duration(milliseconds: 350));
    await tester.pumpAndSettle();
    await tester.tap(find.text('练习'));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.swap_vert), findsNothing);
    expect(find.byTooltip('多选'), findsOneWidget);
    final query = calls.lastWhere((e) => e.method == 'list').arguments as Map;
    expect(query['search'], 'Glorious Crown');
    expect(query['album'], '练习');
    expect(query['ascending'], false);
    expect(query['snapshot'], isA<int>());
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('viewer swipes through photos and releases background work on exit', (tester) async {
    rows = List.generate(3, photo);
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    await tester.tap(find.bySemanticsLabel('Screenshot_0.png'));
    await tester.pumpAndSettle();
    expect(calls.any((e) => e.method == 'viewerStart'), isTrue);
    expect(find.textContaining('1 / 3'), findsOneWidget);
    expect(find.text('重试'), findsWidgets);
    await tester.drag(find.byKey(const Key('rhythm-horizontal-pager')), const Offset(-700, 0));
    await tester.pumpAndSettle();
    expect(find.textContaining('2 / 3'), findsOneWidget);
    // Returning to an already laid out page must retain a valid fit scale.
    for (var i = 0; i < 8; i++) {
      await tester.drag(find.byKey(const Key('rhythm-horizontal-pager')), Offset(i.isEven ? 700 : -700, 0));
      await tester.pumpAndSettle();
      expect(find.textContaining('${i.isEven ? 1 : 2} / 3'), findsOneWidget);
      for (final view in tester.widgetList<AvesMagnifier>(find.byType(AvesMagnifier))) {
        expect(view.controller.scale, isNotNull);
        expect(view.controller.scale, closeTo(view.controller.scaleBoundaries!.initialScale, .00001));
      }
      expect(tester.takeException(), isNull);
    }
    await tester.tap(find.byTooltip('收藏').last);
    await tester.pumpAndSettle();
    final batch = calls.lastWhere((e) => e.method == 'batch').arguments as Map;
    expect(batch['ids'], ['photo-1']);
    expect(batch['operation'], 'favorite');
    await tester.tap(find.byType(BackButton));
    await tester.pump(const Duration(milliseconds: 30));
    expect(calls.where((e) => e.method == 'viewEnd'), isEmpty);
    await tester.pumpAndSettle();
    expect(calls.where((e) => e.method == 'viewEnd').length, 1);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('retained viewer page can return, tap and zoom after repeated deactivation', (tester) async {
    final gallery = RhythmGalleryController();
    var taps = 0;
    Widget page(bool active) => MaterialApp(
      home: Scaffold(
        body: RhythmImagePage(
          key: const ValueKey('retained'),
          gallery: gallery,
          item: photo(0),
          active: active,
          toggleControls: () => taps++,
          onZoom: (_) {},
        ),
      ),
    );
    await tester.pumpWidget(page(true));
    await tester.pumpAndSettle();
    for (var i = 0; i < 8; i++) {
      await tester.pumpWidget(page(false));
      await tester.pumpAndSettle();
      await tester.pumpWidget(page(true));
      await tester.pumpAndSettle();
      final view = tester.widget<AvesMagnifier>(find.byType(AvesMagnifier));
      expect(view.controller.scale, closeTo(view.controller.scaleBoundaries!.initialScale, .00001));
      await tester.tapAt(const Offset(300, 100));
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }
    expect(taps, 8);
    await tester.pumpWidget(const SizedBox());
    gallery.dispose();
  });

  testWidgets('thumbnail transition starts IO concurrently and outgoing page survives a slow swipe', (tester) async {
    final gallery = RhythmGalleryController();
    Widget page({required bool active, required bool moving, required bool full}) => MaterialApp(
      home: Scaffold(
        body: RhythmImagePage(
          key: const ValueKey('transition'),
          gallery: gallery,
          item: photo(0),
          active: active,
          moving: moving,
          fullResolution: full,
          toggleControls: () {},
          onZoom: (_) {},
        ),
      ),
    );
    await tester.pumpWidget(page(active: true, moving: false, full: false));
    await tester.pump();
    expect(calls.where((e) => e.method == 'view').length, 1);
    await tester.pumpWidget(page(active: true, moving: false, full: true));
    await tester.pumpAndSettle();
    final original = tester.widget<AvesMagnifier>(find.byType(AvesMagnifier)).controller;
    await tester.pumpWidget(page(active: false, moving: true, full: true));
    await tester.pump(const Duration(seconds: 1));
    expect(tester.widget<AvesMagnifier>(find.byType(AvesMagnifier)).controller, same(original));
    await tester.pumpWidget(page(active: false, moving: false, full: true));
    await tester.pumpAndSettle();
    final reset = tester.widget<AvesMagnifier>(find.byType(AvesMagnifier)).controller;
    expect(reset, isNot(same(original)));
    expect(reset.scale, closeTo(reset.scaleBoundaries!.initialScale, .00001));
    await tester.pumpWidget(const SizedBox());
    gallery.dispose();
  });

  testWidgets('rename offers Other with confirmation and pencil hugs card corner', (tester) async {
    stats['categories'] = [
      {'code': 'c1', 'name': 'Arcaea', 'count': 2, 'thumbnail': ''},
    ];
    stats['otherCount'] = 10;
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    final pencil = find.byTooltip('重命名 Arcaea');
    final anchor = tester.widget<Positioned>(find.ancestor(of: pencil, matching: find.byType(Positioned)).first);
    expect(anchor.right, 0);
    expect(anchor.bottom, 0);
    await tester.tap(pencil);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ListTile, '其他').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(find.text('移入其他？'), findsOneWidget);
    expect(calls.where((e) => e.method == 'renameCategory'), isEmpty);
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    expect(calls.lastWhere((e) => e.method == 'renameCategory').arguments, {'code': 'c1', 'name': '其他'});
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('batch trash requires confirmation without claiming original remains', (tester) async {
    rows = List.generate(2, photo);
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    await tester.longPress(find.bySemanticsLabel('Screenshot_0.png'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除'));
    await tester.pumpAndSettle();
    expect(find.textContaining('移入回收站，可恢复'), findsOneWidget);
    expect(calls.any((e) => e.method == 'batch'), isFalse);
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(calls.any((e) => e.method == 'batch'), isFalse);
    await tester.tap(find.text('删除'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    expect((calls.lastWhere((e) => e.method == 'batch').arguments as Map)['operation'], 'trash');
    await tester.pumpWidget(const SizedBox());
  });

  test('controller pages beyond sixty without duplicates', () async {
    rows = List.generate(63, photo);
    final gallery = RhythmGalleryController();
    await gallery.refresh();
    expect(gallery.items.length, 60);
    expect(gallery.more, true);
    await gallery.loadMore();
    expect(gallery.items.length, 63);
    expect(gallery.items.map((e) => e['id']).toSet().length, 63);
    expect(gallery.more, false);
    gallery.dispose();
  });

  test('late previous search does not overwrite the newest filter', () async {
    final first = Completer<Map<String, Object?>>();
    var count = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method != 'list') return null;
      if (count++ == 0) return first.future;
      return {
        'items': [photo(2)],
        'total': 1,
      };
    });
    final gallery = RhythmGalleryController();
    final previous = gallery.refresh();
    gallery.search = 'new';
    await gallery.refresh();
    first.complete({
      'items': [photo(1)],
      'total': 1,
    });
    await previous;
    expect(gallery.items.single['id'], 'photo-2');
    gallery.dispose();
  });

  testWidgets('worker errors remain visible and diagnostics stay accessible', (tester) async {
    stats['workerError'] = '后台服务启动失败';
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    expect(find.text('后台服务启动失败'), findsOneWidget);
    await tester.tap(find.byTooltip('设置'));
    await tester.pumpAndSettle();
    expect(find.text('复制性能日志'), findsOneWidget);
    expect(find.text('后台通知设置'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('two fingers zoom without turning page, one finger still pages after reset', (tester) async {
    rows = List.generate(3, photo);
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    await tester.tap(find.bySemanticsLabel('Screenshot_0.png'));
    await tester.pumpAndSettle();
    final center = tester.getCenter(find.byKey(const Key('rhythm-horizontal-pager')));
    final left = await tester.startGesture(center - const Offset(50, 0), pointer: 1);
    await tester.pump(const Duration(milliseconds: 20));
    final right = await tester.startGesture(center + const Offset(50, 0), pointer: 2);
    await left.moveBy(const Offset(-100, 0));
    await right.moveBy(const Offset(100, 0));
    await tester.pump();
    await left.moveBy(const Offset(-80, 0));
    await right.moveBy(const Offset(80, 0));
    await tester.pump();
    await left.up();
    await right.up();
    await tester.pumpAndSettle();
    expect(find.textContaining('1 / 3'), findsOneWidget);
    final magnifier = tester.widget<AvesMagnifier>(find.byType(AvesMagnifier).first);
    expect(magnifier.controller.scale!, greaterThan(magnifier.controller.scaleBoundaries!.initialScale));
    await tester.dragFrom(center, const Offset(0, 180));
    await tester.pumpAndSettle();
    expect(find.byType(RhythmViewer), findsOneWidget);
    expect(calls.any((e) => e.method == 'viewEnd'), isFalse);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets('fullscreen swipe down leaves, short drag cancels, swipe up opens details', (tester) async {
    rows = List.generate(3, photo);
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    await tester.tap(find.bySemanticsLabel('Screenshot_0.png'));
    await tester.pumpAndSettle();
    final shortDrag = await tester.startGesture(const Offset(400, 200));
    await shortDrag.moveBy(const Offset(0, 65));
    await tester.pump(const Duration(milliseconds: 700));
    await shortDrag.up();
    await tester.pumpAndSettle();
    expect(find.byType(RhythmViewer), findsOneWidget);
    await tester.dragFrom(const Offset(400, 450), const Offset(0, -420));
    await tester.pumpAndSettle();
    expect(find.text('照片详情'), findsOneWidget);
    await tester.tap(find.byTooltip('返回照片'));
    await tester.pumpAndSettle();
    await tester.tapAt(const Offset(300, 150));
    await tester.pump(const Duration(milliseconds: 350));
    await tester.pumpAndSettle();
    expect(find.byTooltip('上一张'), findsNothing);
    await tester.dragFrom(const Offset(400, 170), const Offset(0, 400));
    await tester.pumpAndSettle();
    expect(find.byType(RhythmViewer), findsNothing);
    expect(calls.any((e) => e.method == 'viewEnd'), isTrue);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('collection indexes beyond first page and restores viewed photo location', (tester) async {
    rows = List.generate(260, photo);
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    final requested = calls.where((e) => e.method == 'list').map((e) => e.arguments as Map);
    expect(requested.any((args) => args['offset'] == 60 && args['limit'] == 240), isTrue);
    await tester.drag(find.byType(CustomScrollView), const Offset(0, -400));
    await tester.pumpAndSettle();
    // The Aves thumb appears after scrolling, then covers the complete index.
    await tester.dragFrom(const Offset(788, 60), const Offset(0, 270));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('many date groups only mount visible photo rows', (tester) async {
    rows = List.generate(260, (i) => {...photo(i), 'taken': 1700000000000 - i * 86400000});
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    final mountedTiles = find.byWidgetPredicate((w) => w is Semantics && (w.properties.label?.startsWith('Screenshot_') ?? false));
    expect(mountedTiles.evaluate().length, lessThan(40));
    expect(find.byType(SliverVariedExtentList), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('album home exposes category and rename merge hint', (tester) async {
    stats['categories'] = [
      {'code': 'c1', 'name': '', 'count': 2, 'thumbnail': ''},
    ];
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    expect(find.text('全部截图'), findsOneWidget);
    expect(find.text('其他'), findsOneWidget);
    expect(find.text('未命名'), findsOneWidget);
    expect(calls.any((e) => e.method == 'view'), isFalse);
    await tester.longPress(find.text('未命名'));
    await tester.pumpAndSettle();
    expect(find.text('合并到其他相册'), findsOneWidget);
    await tester.tap(find.text('重命名'));
    await tester.pumpAndSettle();
    expect(find.textContaining('与已有相册同名将自动合并'), findsOneWidget);
    await tester.enterText(find.byType(TextField).last, 'Test Game');
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(calls.lastWhere((e) => e.method == 'renameCategory').arguments, {'code': 'c1', 'name': 'Test Game'});
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets('pencil opens editable dropdown and selecting works before any item is chosen', (tester) async {
    stats['categories'] = [
      {'code': 'c1', 'name': 'Arcaea', 'count': 2, 'thumbnail': ''},
      {'code': 'c2', 'name': 'AstroDX', 'count': 5, 'thumbnail': ''},
    ];
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('重命名 Arcaea'));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(ListTile, 'AstroDX'), findsWidgets);
    await tester.enterText(find.byType(TextField).last, 'Ast');
    await tester.pumpAndSettle();
    final dialog = find.byType(AlertDialog);
    expect(find.descendant(of: dialog, matching: find.text('Arcaea')), findsNothing);
    await tester.tap(find.descendant(of: dialog, matching: find.text('AstroDX')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(calls.lastWhere((e) => e.method == 'renameCategory').arguments, {'code': 'c1', 'name': 'AstroDX'});
    await tester.tap(find.byTooltip('多选'));
    await tester.pumpAndSettle();
    expect(find.text('已选 0 个相册'), findsOneWidget);
    expect(find.byType(Checkbox), findsWidgets);
    expect(find.text('导出到文件夹'), findsOneWidget);
    await tester.tap(find.byTooltip('全选'));
    await tester.pumpAndSettle();
    expect(find.textContaining('已选 '), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('complete multi-selection spans unloaded pages and supports achievement overrides', (tester) async {
    rows = List.generate(260, photo);
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('多选'));
    await tester.pumpAndSettle();
    expect(find.text('已选 0 张'), findsOneWidget);
    await tester.tap(find.byTooltip('全选'));
    await tester.pumpAndSettle();
    expect(find.text('已选 260 张'), findsOneWidget);
    await tester.ensureVisible(find.text('成绩标签'));
    await tester.tap(find.text('成绩标签'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('AP · All Perfect / Pure Memory'));
    await tester.pumpAndSettle();
    final args = calls.lastWhere((e) => e.method == 'batch').arguments as Map;
    expect((args['ids'] as List).length, 260);
    expect(args['operation'], 'achievement');
    expect(args['value'], 'AP');
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('move photos offers searchable album choices and moves into home category', (tester) async {
    rows = List.generate(2, photo);
    stats['categories'] = [
      {'code': 'arcaea', 'name': 'Arcaea', 'count': 237, 'thumbnail': ''},
      {'code': 'phira', 'name': 'Phira', 'count': 53, 'thumbnail': ''},
    ];
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    await tester.longPress(find.bySemanticsLabel('Screenshot_0.png'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '相册'));
    await tester.pumpAndSettle();
    expect(find.text('移入相册'), findsOneWidget);
    expect(find.widgetWithText(ListTile, 'Arcaea'), findsOneWidget);
    expect(find.widgetWithText(ListTile, '其他'), findsOneWidget);
    expect(find.text('237 张'), findsOneWidget);
    await tester.enterText(find.byType(TextField).last, 'arc');
    await tester.pumpAndSettle();
    expect(find.widgetWithText(ListTile, 'Phira'), findsNothing);
    await tester.tap(find.widgetWithText(ListTile, 'Arcaea'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '保存'));
    await tester.pumpAndSettle();
    final args = calls.lastWhere((call) => call.method == 'correctCategory').arguments as Map;
    expect(args['ids'], ['photo-0']);
    expect(args['name'], 'Arcaea');
    expect(args['other'], false);
    await tester.pumpWidget(const SizedBox());
  });
}
