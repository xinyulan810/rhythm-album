import 'dart:async';

import 'package:aves/main_libre.dart' as entry;
import 'package:aves/rhythm/rhythm_app.dart';
import 'package:aves/rhythm/rhythm_gallery_controller.dart';
import 'package:aves/rhythm/rhythm_library_page.dart';
import 'package:aves/rhythm/rhythm_viewer.dart';
import 'package:aves/rhythm/rhythm_motion.dart';
import 'package:aves/rhythm/rhythm_song.dart';
import 'package:aves/rhythm/rhythm_song_layout.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:leak_tracker/leak_tracker.dart';
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
  var remembered = <String>[];

  setUp(() {
    calls.clear();
    rows = [];
    access = 'none';
    stats.clear();
    remembered = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      final args = call.arguments as Map? ?? {};
      if (call.method == 'searchHistory') return remembered;
      if (call.method == 'rememberSearch') {
        final text = args['query'] as String;
        remembered = [text,...remembered.where((s) => s != text)].take(5).toList();
        return remembered;
      }
      if (call.method == 'list') {
        final offset = args['offset'] as int? ?? 0;
        final related = (args['relatedTo'] as String? ?? '').isEmpty ? null :
            rows.firstWhere((row) => row['id'] == args['relatedTo']);
        final filtered = rows.where((row) =>
          ((args['songId'] as num? ?? 0) == 0 || row['songId'] == args['songId']) &&
          (related == null || (row['id'] != related['id'] &&
            (((related['songId'] as num? ?? 0) > 0 && row['songId'] == related['songId']) ||
            row['song'] == related['song'] ||
            ((related['song'] as String? ?? '').contains('Custom') && (row['song'] as String? ?? '').contains('Custom'))))) &&
          row['id'] != args['excludeId'] && row['trashed'] != 1).toList();
        return {
          'items': filtered.skip(offset).take(args['limit'] as int? ?? 60).toList(),
          'total': filtered.length,
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
      if (call.method == 'songProgress') return stats['songProgress'];
      if (call.method == 'showSongMissing') {
        stats['showSongMissing'] = args['enabled'];
        return true;
      }
      if (call.method == 'batch' && args['operation'] == 'trash') {
        for (final row in rows) {
          if ((args['ids'] as List).contains(row['id'])) row['trashed'] = 1;
        }
        return (args['ids'] as List).length;
      }
      if (call.method == 'songInfo') return rows.firstWhere((r) => r['id'] == args['id']);
      if (call.method == 'songLayoutScan') {
        return {
        'code': 'phira', 'width': 1000, 'height': 500, 'region': <String,double>{},
        'text': args['region'] == null ? '' : 'Scanned title',
        'lines': [{'x': .3,'y': .1,'right': .7,'bottom': .2,'text': 'Scanned title','engine': 'latin'}],
        };
      }
      if (call.method == 'songLayoutSave') return {...photo(0), 'song': args['title']};
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

  testWidgets('release entry opens screenshot gallery without upstream media services', (tester) async {
    stats['managedChoiceMade'] = true;
    try {
      entry.main();
      await tester.pumpAndSettle();
      expect(find.byType(RhythmLibraryPage), findsOneWidget);
      expect(calls.where((call) => call.method == 'list'), isNotEmpty);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    } finally {
      // main() enables debug leak tracking; cancel its timer before test invariants.
      LeakTracking.stop();
    }
  });

  testWidgets('song confirmation preserves custom OCR title unless a candidate is selected', (tester) async {
    Map<String, dynamic>? answer;
    final item = {...photo(0), 'song': 'Custom Light', 'songRaw': 'Custom Light', 'songState': 'candidate',
      'songCandidates': '[{"id":12,"title":"Official Light","artist":"Composer"}]'};
    await tester.pumpWidget(MaterialApp(home: Builder(builder: (context) =>
      TextButton(onPressed: () async { answer = await editRhythmSong(context, item); }, child: const Text('open')))));
    await tester.tap(find.text('open')); await tester.pumpAndSettle();
    expect(find.text('OCR 原文：Custom Light'), findsOneWidget);
    await tester.tap(find.text('确认')); await tester.pumpAndSettle();
    expect(answer?['songId'], 0);
    expect(answer?['title'], 'Custom Light');
    await tester.tap(find.text('open')); await tester.pumpAndSettle();
    await tester.tap(find.text('Official Light')); await tester.pumpAndSettle();
    await tester.tap(find.text('确认')); await tester.pumpAndSettle();
    expect(answer?['songId'], 12);
    expect(answer?['title'], 'Official Light');
    await tester.pumpWidget(const SizedBox());
  });

  test('same-song controller sends song identity rather than a fuzzy search', () async {
    final controller = RhythmGalleryController()..songId = 42;
    await controller.refresh();
    expect((calls.lastWhere((c) => c.method == 'list').arguments as Map)['songId'], 42);
    expect(controller.search, '');
    controller.dispose();
  });

  testWidgets('search clear cancels pending query and recent five persist as focus chips', (tester) async {
    remembered = ['old'];
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('rhythm-search-history')),findsNothing);
    await tester.tap(find.byType(TextField).first);
    await tester.pumpAndSettle();
    expect(find.widgetWithText(InputChip,'old'),findsOneWidget);
    for (final text in ['one','two','three','four','five','six']) {
      await tester.enterText(find.byType(TextField).first,text);
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await tester.pumpAndSettle();
    }
    expect(remembered,['six','five','four','three','two']);
    await tester.enterText(find.byType(TextField).first,'unfinished');
    await tester.tap(find.byTooltip('清空搜索'));
    await tester.pumpAndSettle();
    expect((calls.lastWhere((c) => c.method == 'list').arguments as Map)['search'],'');
    expect(find.byTooltip('清空搜索'),findsNothing);
    expect(find.byType(InputChip),findsNWidgets(5));
    await tester.tap(find.widgetWithText(InputChip,'six'));
    await tester.pumpAndSettle();
    expect((calls.lastWhere((c) => c.method == 'list').arguments as Map)['search'],'six');
    await tester.pumpWidget(const SizedBox());
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(TextField).first);
    await tester.pumpAndSettle();
    expect(find.widgetWithText(InputChip,'six'),findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('unconfirmed OCR title can find and open related pictures', (tester) async {
    rows = [
      {...photo(0),'songId':0,'song':'Unknown custom','songState':'unmatched'},
      {...photo(1),'songId':0,'song':'Unknown custom','songState':'unmatched'},
    ];
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: RhythmSongRelated(
      row: rows.first,onReturn: () async {},onConfirm: () {}))));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('related-photo-1')),findsOneWidget);
    expect(find.textContaining('曲名文字相似'),findsOneWidget);
    expect(find.text('确认曲名后查找同曲截图'),findsNothing);
    final query = calls.lastWhere((c) => c.method == 'list').arguments as Map;
    expect(query['relatedTo'],'photo-0');
    expect(query['songId'],0);
    await tester.tap(find.byKey(const ValueKey('related-photo-1')));
    await tester.pumpAndSettle();
    expect(calls.lastWhere((c) => c.method == 'view').arguments,{'id':'photo-1'});
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('returning from album resets search scope including recent searches', (tester) async {
    rows = [photo(0)];
    remembered = ['kyatto'];
    stats['categories'] = [{'code': 'arcaea','name': 'Arcaea','count': 1,'thumbnail': ''}];
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Arcaea'));
    await tester.pumpAndSettle();
    expect((calls.lastWhere((c) => c.method == 'list').arguments as Map)['category'],'arcaea');
    await tester.tap(find.descendant(of: find.byType(AppBar),matching: find.widgetWithIcon(IconButton,Icons.close)));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first,'chongqing');
    await tester.pumpAndSettle();
    final query = calls.lastWhere((c) => c.method == 'list').arguments as Map;
    expect(query['category'],'');
    expect(query['album'],'');
    expect(query['search'],'chongqing');
    await tester.tap(find.widgetWithText(InputChip,'kyatto'));
    await tester.pumpAndSettle();
    expect((calls.lastWhere((c) => c.method == 'list').arguments as Map)['category'],'');
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('unrecognized album and photo badges expose song area action on selection', (tester) async {
    rows = [photo(0)];
    stats['songMissing'] = 1;
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    expect(find.byType(RhythmSongMissingBadge),findsOneWidget);
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    expect(find.byType(RhythmSongMissingBadge),findsOneWidget);
    await tester.longPress(find.byType(RhythmSongMissingBadge));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(TextButton,'选择歌名位置'),findsOneWidget);
    expect(tester.widget<TextButton>(find.widgetWithText(TextButton,'选择歌名位置')).onPressed,isNotNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('missing title hint switch hides badges across album photo and viewer', (tester) async {
    rows = [photo(0)];
    stats['songMissing'] = 1;
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    expect(find.byType(RhythmSongMissingBadge),findsOneWidget);
    await tester.tap(find.byTooltip('设置'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('显示未识别曲名提示'));
    await tester.tap(find.widgetWithText(SwitchListTile,'显示未识别曲名提示'));
    await tester.pumpAndSettle();
    expect(stats['showSongMissing'],false);
    expect(find.byType(RhythmSongMissingBadge),findsNothing);
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    expect(find.byType(RhythmSongMissingBadge),findsNothing);
    await tester.tap(find.bySemanticsLabel('Screenshot_0.png'));
    await tester.pumpAndSettle();
    expect(find.byType(RhythmSongMissingBadge),findsNothing);
    expect(find.text('未识别曲名'),findsNothing);
    expect(rows.single['song'],'');
    await tester.pumpWidget(const SizedBox());
    stats['showSongMissing'] = true;
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    expect(find.byType(RhythmSongMissingBadge),findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('enlarged related strip supports multi-select recoverable deletion', (tester) async {
    rows = List.generate(3,(i) => {...photo(i),'song':'Same song'});
    var refreshed = false;
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: RhythmSongRelated(
      row: rows.first,onReturn: () async { refreshed = true; },onConfirm: () {}))));
    await tester.pumpAndSettle();
    final first = find.byKey(const ValueKey('related-photo-1'));
    expect(tester.getSize(first).width,288);
    expect(tester.getSize(first).height,252);
    await tester.longPress(first);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('related-photo-2')));
    await tester.pumpAndSettle();
    expect(find.text('已选 2 张'),findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton,'删除'));
    await tester.pumpAndSettle();
    expect(find.text('移入回收站？'),findsOneWidget);
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    expect(rows.skip(1).every((row) => row['trashed'] == 1),isTrue);
    expect(refreshed,isTrue);
    expect(find.byKey(const ValueKey('related-photo-1')),findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('song progress shares import area and updates inside an album', (tester) async {
    rows = [photo(0)];
    stats['songProgress'] = {'total': 10,'completed': 3,'matched': 2,'failed': 1,'waiting': 0,'paused': true,'parallel': 3};
    await tester.pumpWidget(const MaterialApp(home: RhythmLibraryPage()));
    await tester.pumpAndSettle();
    expect(find.text('曲名识别 · 已暂停 · 3 / 10'), findsOneWidget);
    expect(find.textContaining('3 路并行'), findsOneWidget);
    await tester.tap(find.text('全部截图'));
    await tester.pumpAndSettle();
    stats['songProgress'] = {'total': 10,'completed': 10,'matched': 8,'failed': 1,'waiting': 0};
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('rhythm-song-progress')), findsNothing);
    expect(calls.any((c) => c.method == 'songProgress'), isTrue);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('deleting an earlier related photo keeps the viewer on its current photo', (tester) async {
    rows = List.generate(3,(i) => {...photo(i),'song':'Same song'});
    final controller = RhythmGalleryController();
    await controller.refresh();
    await tester.pumpWidget(MaterialApp(home: RhythmViewer(gallery: controller,initialIndex: 1)));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('详情'));
    await tester.pumpAndSettle();
    await tester.longPress(find.byKey(const ValueKey('related-photo-0')));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton,'删除'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    expect(controller.items.map((row) => row['id']),['photo-1','photo-2']);
    await tester.tap(find.byTooltip('返回照片'));
    await tester.pumpAndSettle();
    expect(find.textContaining('1 / 2'),findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('details loads only other same-song pictures and tap opens selected picture', (tester) async {
    rows = [
      {...photo(0),'songId': 7,'song': 'Same song'},
      {...photo(1),'songId': 7,'song': 'Same song'},
      {...photo(2),'songId': 8,'song': 'Different song'},
    ];
    final controller = RhythmGalleryController();
    await controller.refresh();
    await tester.pumpWidget(MaterialApp(home: RhythmViewer(gallery: controller,initialIndex: 0)));
    await tester.pumpAndSettle();
    expect((tester.widget<Text>(find.byKey(const Key('rhythm-viewer-song')))).data,'Same song');
    expect(tester.getTopLeft(find.byKey(const Key('rhythm-viewer-position'))).dy,
      greaterThan(tester.getTopLeft(find.byKey(const Key('rhythm-viewer-song'))).dy));
    expect(calls.where((c) => c.method == 'list' && (c.arguments as Map)['songId'] == 7),isEmpty);
    await tester.tap(find.byTooltip('详情'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('related-photo-0')),findsNothing);
    expect(find.byKey(const ValueKey('related-photo-1')),findsOneWidget);
    expect(find.byKey(const ValueKey('related-photo-2')),findsNothing);
    await tester.tap(find.byKey(const ValueKey('related-photo-1')));
    await tester.pumpAndSettle();
    expect(calls.lastWhere((c) => c.method == 'view').arguments,{'id': 'photo-1'});
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('tapping OCR text scans relative region and saves corrected title', (tester) async {
    await tester.pumpWidget(MaterialApp(home: RhythmSongLayoutPage(
      row: {...photo(0),'categoryCode':'renamed-album','songLayoutCode':'phira'},imagePath:'missing-test-image.jpg')));
    await tester.pumpAndSettle();
    final rect = tester.getRect(find.byKey(const Key('song-layout-canvas')));
    await tester.tapAt(Offset(rect.left+rect.width*.5,rect.top+rect.height*.15));
    await tester.pumpAndSettle();
    final scan = calls.lastWhere((c) => c.method == 'songLayoutScan').arguments as Map;
    expect(scan['region'],isNotNull);
    expect((scan['region'] as Map)['x'],closeTo(.294,.001));
    expect(find.textContaining('绑定识别布局：phira'),findsOneWidget);
    await tester.enterText(find.byType(TextField),'Corrected song');
    await tester.ensureVisible(find.text('保存位置与本图歌名'));
    await tester.tap(find.text('保存位置与本图歌名'));
    await tester.pumpAndSettle();
    final saved = calls.lastWhere((c) => c.method == 'songLayoutSave').arguments as Map;
    expect(saved['title'],'Corrected song');
    expect(saved.containsKey('categoryCode'),isFalse);
    await tester.pumpWidget(const SizedBox());
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
    await tester.ensureVisible(find.text('删除'));
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
