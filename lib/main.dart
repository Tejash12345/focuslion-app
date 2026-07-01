import 'dart:async';
import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_timezone/flutter_timezone.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:timezone/data/latest.dart' as tzdata;
import 'package:timezone/timezone.dart' as tz;
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

import 'lion_3d.dart';
import 'reminders.dart';

const siteUrl = 'https://new-app-ruddy-nine.vercel.app';

// .trim() guards against a stray space in the key: harmless for REST (header
// whitespace is stripped) but it breaks the realtime WebSocket, where the key
// rides in the URL query string as %20 and Supabase rejects the connection.
final supabaseUrl = 'https://hgnbgnzgciooifwyfbgn.supabase.co'.trim();
final supabaseAnonKey = 'sb_publishable_ayNbzdRu6Utt4BN3Zhc_lg_mnQuIJV0'.trim();

// hosts that stay INSIDE the WebView. Anything else (e.g. a Learning Path
// reference link to docs/YouTube) is handed to the system browser so the user
// keeps their place in the app instead of the WebView navigating away.
final _siteHost = Uri.parse(siteUrl).host;
final _supabaseHost = Uri.parse(supabaseUrl).host;

/// Decides whether a navigation stays in the WebView or opens externally.
/// Off-site http(s) links and non-web schemes (mailto:, tel:, intent:…) the
/// WebView can't render are launched in the system browser.
Future<NavigationDecision> _routeNavigation(NavigationRequest request) async {
  final uri = Uri.tryParse(request.url);
  if (uri == null) return NavigationDecision.navigate;
  final scheme = uri.scheme.toLowerCase();
  final isWeb = scheme == 'http' || scheme == 'https';
  // keep the app's own pages and Supabase auth/redirects in the WebView
  if (isWeb && (uri.host.isEmpty || uri.host == _siteHost || uri.host == _supabaseHost)) {
    return NavigationDecision.navigate;
  }
  // internal schemes the WebView handles itself
  if (scheme == 'about' || scheme == 'blob' || scheme == 'data' || scheme == 'file') {
    return NavigationDecision.navigate;
  }
  try {
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  } catch (_) {/* if nothing can open it, just swallow — better than a crash */}
  return NavigationDecision.prevent;
}
// localStorage key supabase-js uses for the session inside the WebView
const authStorageKey = 'sb-hgnbgnzgciooifwyfbgn-auth-token';

const channel = MethodChannel('focuslion/guard');

/// Maps the web app's social_limits.app_name to Android package names.
const appPackages = <String, String>{
  'Instagram': 'com.instagram.android',
  'YouTube': 'com.google.android.youtube',
  'Facebook': 'com.facebook.katana',
  'X (Twitter)': 'com.twitter.android',
  'TikTok': 'com.zhiliaoapp.musically',
  'Snapchat': 'com.snapchat.android',
};
const appEmojis = <String, String>{
  'Instagram': '📸', 'YouTube': '▶️', 'Facebook': '👥',
  'X (Twitter)': '🐦', 'TikTok': '🎵', 'Snapchat': '👻',
};

/// Reverse of [appPackages]: Android package name -> the web app's display name.
/// Used to translate the native usage-stats (keyed by package) into the names
/// the Wellbeing page understands before pushing them into the WebView.
final pkgToName = <String, String>{
  for (final e in appPackages.entries) e.value: e.key,
};

final _notifs = FlutterLocalNotificationsPlugin();
const _notifChannel = 'focuslion_app';

// text-to-speech for the web app. Android WebView has no Web Speech API, so the
// web posts to the FLSpeak channel and we read it aloud natively.
//
// Two message formats are accepted (see web src/lib/speak.ts):
//   • plain text  -> speak it (legacy: Word of the Day pronounce)
//   • JSON command -> {a:'speak',text,lang,rate} | {a:'pause'} | {a:'resume'} | {a:'stop'}
// For a JSON 'speak' we split into sentences and play them as a queue, so
// pause / resume / stop work even though Android TTS has no native resume
// (resume replays the current sentence, then continues). The web is told
// controls exist via window.__FLSpeakV2, and we call window.__flSpeakEnded()
// when playback finishes so its button resets to "Listen".
final FlutterTts _tts = FlutterTts();
List<String> _ttsChunks = [];
int _ttsPos = 0;
bool _ttsPlaying = false;

int _ttsRun = 0; // generation guard so a stale loop can't keep speaking

Future<void> _initTts() async {
  try {
    // resolve `await speak()` only when the phrase actually finishes — this is
    // what makes the sentence-by-sentence loop (and pause/resume) reliable.
    await _tts.awaitSpeakCompletion(true);
    await _tts.setLanguage('en-US');
    await _tts.setSpeechRate(0.45); // 1.0 is too fast on Android
    await _tts.setVolume(1.0);
    await _tts.setPitch(1.0);
  } catch (_) {}
}

void _ttsNotifyEnded() {
  _activeController?.runJavaScript('window.__flSpeakEnded && window.__flSpeakEnded();');
}

List<String> _splitSentences(String text) {
  final parts = RegExp(r'\S[^.!?\n]*[.!?\n]*')
      .allMatches(text)
      .map((m) => m.group(0)!.trim())
      .where((s) => s.isNotEmpty)
      .toList();
  return parts.isEmpty ? <String>[text] : parts;
}

// Play the queue from _ttsPos. `await speak()` resolves per-sentence (see
// awaitSpeakCompletion above); pausing/stopping flips _ttsPlaying and stops the
// engine, which resolves the await so the loop exits WITHOUT advancing _ttsPos —
// so resume replays the current sentence, then continues.
Future<void> _ttsPlayLoop(int gen) async {
  while (_ttsPlaying && gen == _ttsRun && _ttsPos < _ttsChunks.length) {
    try {
      await _tts.speak(_ttsChunks[_ttsPos]);
    } catch (_) {}
    if (!_ttsPlaying || gen != _ttsRun) return; // paused / stopped / superseded
    _ttsPos++;
  }
  if (_ttsPlaying && gen == _ttsRun) {
    _ttsPlaying = false;
    _ttsNotifyEnded(); // reached the end on its own -> reset the web button
  }
}

Future<void> _speak(String msg) async {
  final raw = msg.trim();
  if (raw.isEmpty) return;
  try {
    // legacy plain text -> speak once
    if (!raw.startsWith('{')) {
      _ttsRun++;
      _ttsPlaying = false;
      await _tts.stop();
      await _tts.setLanguage('en-US');
      await _tts.setSpeechRate(0.45);
      _ttsChunks = _splitSentences(raw);
      _ttsPos = 0;
      _ttsPlaying = true;
      unawaited(_ttsPlayLoop(_ttsRun));
      return;
    }
    final cmd = jsonDecode(raw) as Map<String, dynamic>;
    switch (cmd['a']) {
      case 'speak':
        _ttsRun++;
        _ttsPlaying = false;
        await _tts.stop();
        final lang = cmd['lang'];
        if (lang is String && lang.isNotEmpty) {
          // If the phone has no voice data for this language, Android "speaks"
          // silently — so tell the web to guide the user to install it instead
          // of looking broken.
          bool available = true;
          try {
            available = (await _tts.isLanguageAvailable(lang)) == true;
          } catch (_) {}
          if (!available) {
            _activeController?.runJavaScript(
                'window.__flSpeakNoVoice && window.__flSpeakNoVoice(${jsonEncode(lang)});');
            _ttsNotifyEnded();
            return;
          }
          await _tts.setLanguage(lang);
        }
        final rate = cmd['rate'];
        // web sends ~0.9; Android TTS is much faster, so scale down for clarity
        await _tts.setSpeechRate(rate is num ? (rate.toDouble() * 0.5) : 0.45);
        _ttsChunks = _splitSentences((cmd['text'] as String?)?.trim() ?? '');
        _ttsPos = 0;
        _ttsPlaying = true;
        unawaited(_ttsPlayLoop(_ttsRun));
        break;
      case 'pause':
        _ttsPlaying = false; // loop exits without advancing _ttsPos
        await _tts.stop();
        break;
      case 'resume':
        if (!_ttsPlaying && _ttsChunks.isNotEmpty && _ttsPos < _ttsChunks.length) {
          _ttsPlaying = true;
          _ttsRun++;
          unawaited(_ttsPlayLoop(_ttsRun));
        }
        break;
      case 'stop':
        _ttsRun++;
        _ttsPlaying = false;
        _ttsChunks = [];
        _ttsPos = 0;
        await _tts.stop();
        _ttsNotifyEnded();
        break;
    }
  } catch (_) {}
}

// this device's FCM token, cached so we can (re)save it to Supabase whenever a
// session becomes available
String? _fcmToken;

// ---- deep linking: tapping a notification opens the matching web page ----
String? _pendingDeepLink;          // set if a tap arrives before the WebView is ready
WebViewController? _activeController;

/// Maps a notification's data payload to the web-app route to open. For DMs we
/// include the sender id so tapping the notification opens THAT conversation
/// directly (Instagram-style) — the web ChatPage handles /chat?dm=<id>.
String? _routeForType(Map<String, dynamic> data) {
  final type = data['type'] as String?;
  final sender = data['sender'] as String?;
  switch (type) {
    case 'friend_request':
      return '/friends';
    case 'friend_accept':
      // they accepted — open the chat to say hi (falls back to the friends list)
      return (sender != null && sender.isNotEmpty) ? '/chat?dm=$sender' : '/friends';
    case 'like':
    case 'comment':
    case 'repost':
      return '/feed';
    case 'dm':
      return (sender != null && sender.isNotEmpty) ? '/chat?dm=$sender' : '/chat';
    case 'announcement':
    case 'ai_briefing':
      return '/';
    default:
      return null;
  }
}

/// Navigates the in-app WebView to [route] (client-side, no full reload). If the
/// WebView isn't ready yet (app launched by tapping a notification from a killed
/// state), the route is held and applied once the first page finishes loading.
void _openRoute(String? route) {
  if (route == null || route.isEmpty) return;
  final ctrl = _activeController;
  if (ctrl == null) {
    _pendingDeepLink = route;
    return;
  }
  ctrl.runJavaScript(
    "try{window.history.pushState({},'','$route');"
    "window.dispatchEvent(new PopStateEvent('popstate'));}catch(e){}",
  );
}

// Handles pushes that arrive while the app is backgrounded or killed. Runs in
// its own isolate, so it must initialize Firebase itself. Android shows
// notification-type messages in the system tray automatically; this is mainly
// for logging and any future data-message handling.
@pragma('vm:entry-point')
Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  await Firebase.initializeApp();
  debugPrint('FCM (background): ${message.messageId} '
      '"${message.notification?.title}" / "${message.notification?.body}" '
      'data=${message.data}');
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  try {
    // The WebView's JS supabase client is the SINGLE owner of the auth session
    // and the only one allowed to refresh/rotate the refresh token. This native
    // client only mirrors that session (see _onAuthToken), so its background
    // auto-refresh is disabled: two clients refreshing the same refresh-token
    // lineage rotate each other's tokens out from under them, which invalidated
    // the WebView's stored token and intermittently kicked the user back to the
    // login page on app open.
    await Supabase.initialize(
      url: supabaseUrl,
      publishableKey: supabaseAnonKey,
      authOptions: const FlutterAuthClientOptions(autoRefreshToken: false),
    );
  } catch (_) {}
  await _initTimezone();
  await _initNotifications();
  await _initTts();
  try {
    await Firebase.initializeApp();
    await _initFirebaseMessaging();
  } catch (e) {
    debugPrint('Firebase init failed: $e');
  }
  runApp(const FocusLionApp());
}

SupabaseClient get db => Supabase.instance.client;

/// Sets the device's local timezone so scheduled reminders fire at the right
/// local time.
Future<void> _initTimezone() async {
  try {
    tzdata.initializeTimeZones();
    final dynamic t = await FlutterTimezone.getLocalTimezone();
    final name = (t is String) ? t : (t.identifier as String);
    tz.setLocalLocation(tz.getLocation(name));
  } catch (_) {
    try {
      tz.setLocalLocation(tz.getLocation('UTC'));
    } catch (_) {}
  }
}

Future<void> _initNotifications() async {
  try {
    const androidInit = AndroidInitializationSettings('ic_stat_notification');
    await _notifs.initialize(
      const InitializationSettings(android: androidInit),
      // tapping a notification shown while the app is open deep-links via payload
      onDidReceiveNotificationResponse: (resp) => _openRoute(resp.payload),
    );
    final android = _notifs
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();
    await android?.createNotificationChannel(const AndroidNotificationChannel(
      _notifChannel, 'FocusLion',
      description: 'Reminders and feed activity',
      importance: Importance.high,
    ));
    await _scheduleDailyReminders();
    // permission is requested through a friendly in-app prompt on first launch
    // (see _maybePromptNotifications), not silently here
  } catch (_) {}
}

/// Schedules recurring on-device reminders (drink water, stretch breaks, wind
/// down) that fire even when the app is closed — via Android's alarm scheduler.
/// Daily-repeating; re-scheduling with the same ids each launch keeps them fresh
/// without creating duplicates.
Future<void> _scheduleDailyReminders() async {
  try {
    const details = NotificationDetails(
      android: AndroidNotificationDetails(
        _notifChannel, 'FocusLion',
        channelDescription: 'Reminders and feed activity',
        importance: Importance.high,
        priority: Priority.high,
        icon: 'ic_stat_notification',
        color: Color(0xFF6C8CFF),
      ),
    );
    // [notification id, hour-of-day, title, body]
    final items = <List<Object>>[
      for (final h in [9, 11, 13, 15, 17, 19, 21])
        [700100 + h, h, '💧 Hydration check', 'Time to drink a glass of water. 💦'],
      for (final h in [10, 12, 14, 16, 18, 20])
        [700200 + h, h, '🧘 Stretch break', 'Stand up, stretch, and rest your eyes for a minute.'],
      [700022, 22, '🌙 Wind down', 'Time to wrap up and get good sleep — tomorrow needs you sharp. 🦁'],
    ];
    for (final it in items) {
      await _notifs.zonedSchedule(
        it[0] as int,
        it[2] as String,
        it[3] as String,
        _nextInstanceOfHour(it[1] as int),
        details,
        androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
        uiLocalNotificationDateInterpretation:
            UILocalNotificationDateInterpretation.absoluteTime,
        matchDateTimeComponents: DateTimeComponents.time, // repeat daily at this time
      );
    }
  } catch (e) {
    debugPrint('scheduleReminders failed: $e');
  }
}

tz.TZDateTime _nextInstanceOfHour(int hour) {
  final now = tz.TZDateTime.now(tz.local);
  var d = tz.TZDateTime(tz.local, now.year, now.month, now.day, hour);
  if (!d.isAfter(now)) d = d.add(const Duration(days: 1));
  return d;
}

/// Schedules STUDY reminders from the user's timetable so they fire even when
/// the app is closed. Hydration/break/sleep are scheduled natively above, but
/// study reminders were web-only (the in-app engine only runs while the app is
/// open) — which is why they never arrived. Re-reads the timetable and
/// reschedules weekly-repeating, ~5 min before each block. Needs a signed-in
/// session (called from the auth listener).
Future<void> _scheduleStudyReminders() async {
  try {
    final user = db.auth.currentUser;
    if (user == null) return;
    // clear previously scheduled study reminders (dedicated id range) so
    // edits/removals on the timetable take effect instead of piling up
    for (var id = 720000; id < 720200; id++) {
      await _notifs.cancel(id);
    }
    final rows = await db
        .from('timetable_blocks')
        .select('day_of_week, start_min, title')
        .eq('user_id', user.id);
    const details = NotificationDetails(
      android: AndroidNotificationDetails(
        _notifChannel, 'FocusLion',
        channelDescription: 'Reminders and feed activity',
        importance: Importance.high,
        priority: Priority.high,
        icon: 'ic_stat_notification',
        color: Color(0xFF6C8CFF),
      ),
    );
    var id = 720000;
    for (final r in (rows as List)) {
      if (id >= 720200) break; // safety cap
      final dow = (r['day_of_week'] as num?)?.toInt();    // 0=Mon .. 6=Sun
      final startMin = (r['start_min'] as num?)?.toInt(); // minutes from midnight
      final title = (r['title'] as String?)?.trim();
      if (dow == null || dow < 0 || dow > 6 || startMin == null) continue;
      final remindMin = startMin - 5 < 0 ? 0 : startMin - 5; // nudge 5 min before
      await _notifs.zonedSchedule(
        id++,
        '📚 Study time!',
        '"${(title != null && title.isNotEmpty) ? title : 'Study session'}" starts soon — get ready!',
        _nextInstanceOfDayAndTime(dow + 1, remindMin ~/ 60, remindMin % 60),
        details,
        androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
        uiLocalNotificationDateInterpretation:
            UILocalNotificationDateInterpretation.absoluteTime,
        matchDateTimeComponents: DateTimeComponents.dayOfWeekAndTime, // weekly repeat
      );
    }
  } catch (e) {
    debugPrint('scheduleStudyReminders failed: $e');
  }
}

/// Next occurrence of [weekday] (Dart 1=Mon..7=Sun) at [hour]:[minute].
tz.TZDateTime _nextInstanceOfDayAndTime(int weekday, int hour, int minute) {
  final now = tz.TZDateTime.now(tz.local);
  var d = tz.TZDateTime(tz.local, now.year, now.month, now.day, hour, minute);
  while (d.weekday != weekday || !d.isAfter(now)) {
    d = d.add(const Duration(days: 1));
  }
  return d;
}

/// Shows an Android notification. Called from the web app via the FLNotify
/// JavaScript channel (reminders, new likes/comments on your posts, etc.).
Future<void> showAppNotification(String raw) async {
  try {
    final data = jsonDecode(raw);
    final title = (data is Map && data['title'] != null) ? '${data['title']}' : 'FocusLion';
    final body = (data is Map && data['body'] != null) ? '${data['body']}' : '';
    final tag = (data is Map) ? data['tag'] : null;
    final route = (data is Map) ? data['route'] as String? : null;
    final id = (tag is String ? tag.hashCode : DateTime.now().millisecondsSinceEpoch) & 0x7fffffff;
    await _notifs.show(
      id, title, body,
      const NotificationDetails(
        android: AndroidNotificationDetails(
          _notifChannel, 'FocusLion',
          channelDescription: 'Reminders and feed activity',
          importance: Importance.high,
          priority: Priority.high,
          // branded notification icons: white silhouette in the status bar,
          // full-colour FocusLion logo as the large icon ("app profile")
          icon: 'ic_stat_notification',
          color: Color(0xFF6C8CFF),
          largeIcon: DrawableResourceAndroidBitmap('ic_notification_large'),
        ),
      ),
      payload: route,
    );
  } catch (_) {}
}

/// Wires up Firebase Cloud Messaging. Foreground pushes are shown through the
/// existing local-notifications channel; background/killed pushes go to the
/// top-level [_firebaseMessagingBackgroundHandler]. Prints the device token so
/// a test can be sent from Firebase Console -> Messaging -> Send test message.
Future<void> _initFirebaseMessaging() async {
  final fm = FirebaseMessaging.instance;

  // delivery while the app is backgrounded/killed
  FirebaseMessaging.onBackgroundMessage(_firebaseMessagingBackgroundHandler);

  // Android 13+ permission prompt (harmless if already granted via the app's
  // own notification prompt); also enables alerts on iOS
  await fm.requestPermission();

  // foreground: Android doesn't auto-display, so we show it ourselves
  FirebaseMessaging.onMessage.listen(_showRemoteMessage);

  // tapping a system-tray notification (app backgrounded or killed) opens the
  // matching page in the WebView
  FirebaseMessaging.onMessageOpenedApp.listen((m) {
    _openRoute(_routeForType(m.data));
  });

  // app launched from a killed state by tapping a notification
  final initial = await fm.getInitialMessage();
  if (initial != null) {
    _openRoute(_routeForType(initial.data));
  }

  // the device's FCM token — cached + saved to Supabase so server triggers can
  // target this device. Also printed for manual Firebase Console test sends.
  _fcmToken = await fm.getToken();
  debugPrint('========== FCM TOKEN ==========');
  debugPrint(_fcmToken ?? '(null — no token yet)');
  debugPrint('===============================');
  await _saveFcmToken();
  fm.onTokenRefresh.listen((t) {
    _fcmToken = t;
    debugPrint('FCM token refreshed: $t');
    _saveFcmToken();
  });

  // whenever a Supabase session becomes available (via the WebView SSO bridge
  // or manual login), persist this device's token so the server can target it
  db.auth.onAuthStateChange.listen((state) {
    if (state.session != null) {
      _saveFcmToken();
      _scheduleStudyReminders(); // (re)schedule study reminders from the timetable
    }
  });
}

/// Upserts this device's FCM token into Supabase `user_push_tokens` for the
/// signed-in user, so server-side triggers can push to it. No-op when signed
/// out or before a token exists; the auth listener retries once a session lands.
Future<void> _saveFcmToken() async {
  final user = db.auth.currentUser;
  final token = _fcmToken;
  if (user == null || token == null || token.isEmpty) return;
  try {
    await db.from('user_push_tokens').upsert(
      {
        'user_id': user.id,
        'fcm_token': token,
        'platform': 'android',
        'updated_at': DateTime.now().toIso8601String(),
      },
      onConflict: 'fcm_token',
    );
  } catch (e) {
    debugPrint('saveFcmToken failed: $e');
  }
}

/// Converts an incoming push into the JSON shape [showAppNotification] expects
/// and displays it via the existing flutter_local_notifications channel.
void _showRemoteMessage(RemoteMessage message) {
  final n = message.notification;
  final title = n?.title ?? (message.data['title'] as String?) ?? 'FocusLion';
  final body = n?.body ?? (message.data['body'] as String?) ?? '';
  showAppNotification(jsonEncode({
    'title': title,
    'body': body,
    // reuse the server-provided tag so a push and the web app's in-app
    // notification for the same event collapse instead of duplicating
    'tag': (message.data['tag'] as String?) ?? message.messageId,
    'route': _routeForType(message.data),
  }));
}

class FocusLionApp extends StatelessWidget {
  const FocusLionApp({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFF6C8CFF),
      brightness: Brightness.dark,
    );
    return MaterialApp(
      title: 'FocusLion',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: scheme,
        scaffoldBackgroundColor: const Color(0xFF0B0D14),
        cardTheme: CardThemeData(
          color: const Color(0xFF161A28),
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: const Color(0xFF1A1E2C),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(16),
            borderSide: BorderSide.none,
          ),
        ),
      ),
      home: const WebShell(),
    );
  }
}

// ============================================================
//  Main shell — the web app in a WebView + a floating Guard button
// ============================================================
class WebShell extends StatefulWidget {
  const WebShell({super.key});

  @override
  State<WebShell> createState() => _WebShellState();
}

class _WebShellState extends State<WebShell> {
  late final WebViewController controller;
  int progress = 0;
  bool loading = true;
  bool _notifPrompted = false;
  Timer? _usagePushTimer;

  @override
  void dispose() {
    _usagePushTimer?.cancel();
    _activeController = null;
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    controller = WebViewController(
      // voice messages: when the site asks for the microphone, make sure the
      // OS-level mic permission is held, then pass the grant through to the
      // page. Handles the case where the user previously tapped "Don't allow"
      // (Android then never prompts again) by sending them to app settings.
      onPermissionRequest: (request) async {
        if (request.types.contains(WebViewPermissionResourceType.microphone)) {
          var status = await Permission.microphone.status;
          if (!status.isGranted) status = await Permission.microphone.request();
          if (status.isGranted) {
            await request.grant();
          } else {
            await request.deny();
            // permanently denied / restricted: Android won't show the prompt
            // again, so open settings where the user can flip the switch
            if (status.isPermanentlyDenied || status.isRestricted) {
              await openAppSettings();
            }
          }
          return;
        }
        await request.grant();
      },
    )
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      // single sign-on: the page hands its Supabase session to the native side
      // so the Guard can sync your blocking settings without a second login
      ..addJavaScriptChannel('FLAuth', onMessageReceived: (m) => _onAuthToken(m.message))
      // the web app posts notifications here; we show them as real Android ones
      ..addJavaScriptChannel('FLNotify', onMessageReceived: (m) => showAppNotification(m.message))
      // the web app posts text here to be read aloud (WebView has no Web Speech API)
      ..addJavaScriptChannel('FLSpeak', onMessageReceived: (m) => _speak(m.message))
      // the web app pings here whenever blocking limits change, so the native
      // Guard re-syncs and enforces the new caps/hours immediately
      ..addJavaScriptChannel('FLGuard', onMessageReceived: (_) => pushGuardConfig())
      ..setBackgroundColor(const Color(0xFF0B0D14))
      ..setNavigationDelegate(
        NavigationDelegate(
          onNavigationRequest: _routeNavigation,
          onProgress: (p) => setState(() => progress = p),
          onPageStarted: (_) => setState(() => loading = true),
          onPageFinished: (_) {
            setState(() => loading = false);
            // tell the web app the native TTS bridge supports pause/resume/stop
            _activeController?.runJavaScript('window.__FLSpeakV2 = true;');
            _grabSession();
            _checkForUpdate();
            _pushUsageToWeb();
            if (!_notifPrompted) {
              _notifPrompted = true;
              // ask for the battery exemption right after notifications so the
              // scheduled reminders actually fire (sequential — no overlap)
              _maybePromptNotifications().then((_) => _maybePromptBattery());
            }
            // apply a deep link that arrived before the WebView was ready
            if (_pendingDeepLink != null) {
              final r = _pendingDeepLink;
              _pendingDeepLink = null;
              _openRoute(r);
            }
          },
        ),
      )
      ..loadRequest(Uri.parse(siteUrl));

    // photo/document/video sending: Android WebView needs the app to supply a
    // file picker, otherwise <input type=file> does nothing.
    if (controller.platform is AndroidWebViewController) {
      final android = controller.platform as AndroidWebViewController;
      android.setOnShowFileSelector(_pickFiles);
      android.setMediaPlaybackRequiresUserGesture(false);
    }

    // expose this controller so notification taps can deep-link into the WebView
    _activeController = controller;

    // keep the web Wellbeing page's "used today" in sync with the phone's real
    // per-app screen time while the app is open
    _usagePushTimer =
        Timer.periodic(const Duration(seconds: 20), (_) => _pushUsageToWeb());
  }

  /// Reads the phone's real per-app usage (minutes used today) from the native
  /// blocker and injects it into the WebView, so the Wellbeing page can show
  /// actual screen time instead of relying on the manual "start the timer"
  /// button. Keyed by the web app's display names. No-op without Usage Access.
  Future<void> _pushUsageToWeb() async {
    try {
      final raw = await channel.invokeMethod<String>('usage');
      if (raw == null || raw.isEmpty) return;
      final m = jsonDecode(raw) as Map<String, dynamic>;
      final byName = <String, int>{};
      m.forEach((pkg, ms) {
        final name = pkgToName[pkg];
        if (name != null) byName[name] = ((ms as num).toDouble() / 60000).round();
      });
      final json = jsonEncode(byName);
      await controller.runJavaScript(
        "try{window.__FL_USAGE__=$json;"
        "window.dispatchEvent(new CustomEvent('fl-usage',{detail:$json}));}catch(e){}",
      );
    } catch (_) {}
  }

  // pull the supabase session JSON out of the page's localStorage
  void _grabSession() {
    controller.runJavaScript(
      "try{var v=localStorage.getItem('$authStorageKey');"
      "if(!v){var k=Object.keys(localStorage).find(function(x){return x.indexOf('sb-')===0&&x.indexOf('-auth-token')>0});if(k)v=localStorage.getItem(k);}"
      "FLAuth.postMessage(v||'');}catch(e){FLAuth.postMessage('');}",
    );
  }

  // force the PWA service worker to check for a new build on every launch,
  // so new features (like the Feed) show up without the user clearing cache
  void _checkForUpdate() {
    controller.runJavaScript(
      "try{if('serviceWorker' in navigator){"
      "navigator.serviceWorker.getRegistrations().then(function(rs){rs.forEach(function(r){r.update();});});"
      "}}catch(e){}",
    );
  }

  // first-run nudge: ask the user to enable notifications so reminders and
  // feed updates actually arrive. Shown until granted, capped so it never nags.
  Future<void> _maybePromptNotifications() async {
    try {
      if (await Permission.notification.isGranted) return;
      final prefs = await SharedPreferences.getInstance();
      final shown = prefs.getInt('notif_prompts') ?? 0;
      if (shown >= 3) return;
      await prefs.setInt('notif_prompts', shown + 1);
      if (!mounted) return;

      final enable = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: const Color(0xFF161A28),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          title: const Text('🔔 Turn on notifications',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 18)),
          content: const Text(
            'Allow notifications to get study & break reminders, time-up alerts, '
            'and updates when someone likes or comments on your posts.',
            style: TextStyle(color: Colors.white70, height: 1.4),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Maybe later', style: TextStyle(color: Colors.white54)),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFF6C8CFF),
                foregroundColor: Colors.white,
              ),
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Enable'),
            ),
          ],
        ),
      );
      if (enable != true || !mounted) return;

      final result = await Permission.notification.request();
      if (!mounted) return;

      // if the user permanently denied it, the OS dialog won't show again —
      // guide them to the app's settings screen to flip it on
      if (result.isPermanentlyDenied || result.isDenied) {
        final goSettings = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            backgroundColor: const Color(0xFF161A28),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
            title: const Text('Enable in Settings',
                style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 18)),
            content: const Text(
              'To get reminders, turn on notifications for FocusLion in your '
              'phone Settings → Apps → FocusLion → Notifications.',
              style: TextStyle(color: Colors.white70, height: 1.4),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('Not now', style: TextStyle(color: Colors.white54)),
              ),
              FilledButton(
                style: FilledButton.styleFrom(
                  backgroundColor: const Color(0xFF6C8CFF),
                  foregroundColor: Colors.white,
                ),
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('Open Settings'),
              ),
            ],
          ),
        );
        if (goSettings == true) await openAppSettings();
      }
    } catch (_) {}
  }

  // One-time nudge to exempt the app from battery optimization, so Android —
  // and especially aggressive OEMs like Motorola — doesn't kill the scheduled
  // study/break/hydration/sleep reminders (AlarmManager alarms get dropped when
  // the app is "restricted"). Capped so it never nags.
  Future<void> _maybePromptBattery() async {
    try {
      if (await Permission.ignoreBatteryOptimizations.isGranted) return;
      final prefs = await SharedPreferences.getInstance();
      final shown = prefs.getInt('battery_prompts') ?? 0;
      if (shown >= 2) return;
      await prefs.setInt('battery_prompts', shown + 1);
      if (!mounted) return;

      final allow = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: const Color(0xFF161A28),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          title: const Text('🔋 Keep reminders on time',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 18)),
          content: const Text(
            'Allow FocusLion to run without battery restrictions so your study, '
            'break, hydration and sleep reminders fire on time — even when the '
            'app is closed. Otherwise your phone may delay or skip them.',
            style: TextStyle(color: Colors.white70, height: 1.4),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Maybe later', style: TextStyle(color: Colors.white54)),
            ),
            FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFF6C8CFF),
                foregroundColor: Colors.white,
              ),
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Allow'),
            ),
          ],
        ),
      );
      if (allow != true) return;
      await Permission.ignoreBatteryOptimizations.request();
    } catch (_) {}
  }

  Future<void> _onAuthToken(String raw) async {
    if (raw.isEmpty) return;
    try {
      final data = jsonDecode(raw);
      if (data is! Map) return;
      final access = data['access_token'];
      final refresh = data['refresh_token'];
      if (access is! String || access.isEmpty || refresh is! String || refresh.isEmpty) return;

      // Mirror the web app's CURRENT session verbatim — do NOT rotate the
      // refresh token. recoverSession() restores the session as-is and only
      // hits the network when it's already expired, so we skip expiring tokens
      // and let the WebView's client refresh them; _grabSession() runs on every
      // page load, so we pick up the web's freshest token right after it
      // rotates its own. The old setSession(refresh) path rotated the token and
      // invalidated the WebView's stored copy, causing the random re-login.
      final expiresAt = data['expires_at'];
      final nowSec = DateTime.now().millisecondsSinceEpoch ~/ 1000;
      final expiringSoon = expiresAt is int && expiresAt <= nowSec + 30;
      final current = db.auth.currentSession;
      if (!expiringSoon && (current == null || current.accessToken != access)) {
        await db.auth.recoverSession(raw);
      }
      // keep the native blocker's config fresh on each load, so limits changed
      // on another device take effect without opening the Guard screen
      if (db.auth.currentSession != null) await pushGuardConfig();
    } catch (_) {}
  }

  Future<List<String>> _pickFiles(FileSelectorParams params) async {
    final accept = params.acceptTypes
        .map((t) => t.trim())
        .where((t) => t.isNotEmpty)
        .toList();
    final onlyImages = accept.isNotEmpty && accept.every((t) => t.startsWith('image'));
    // reels upload <input accept="video/*"> — let Android open the gallery's
    // video picker instead of a generic file browser
    final onlyVideos = accept.isNotEmpty && accept.every((t) => t.startsWith('video'));
    final result = await FilePicker.platform.pickFiles(
      allowMultiple: params.mode == FileSelectorMode.openMultiple,
      type: onlyImages
          ? FileType.image
          : onlyVideos
              ? FileType.video
              : FileType.any,
    );
    if (result == null) return [];
    return result.files
        .where((f) => f.path != null)
        .map((f) => Uri.file(f.path!).toString())
        .toList();
  }

  Future<bool> _onBack() async {
    if (await controller.canGoBack()) {
      controller.goBack();
      return false;
    }
    return true;
  }

  void _openGuard() {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => const GuardScreen()));
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        final shouldPop = await _onBack();
        if (shouldPop && mounted) {
          SystemNavigator.pop();
        }
      },
      child: Scaffold(
        backgroundColor: const Color(0xFF0B0D14),
        body: SafeArea(
          child: Stack(
            children: [
              RefreshIndicator(
                onRefresh: () => controller.reload(),
                child: ListView(
                  physics: const ClampingScrollPhysics(),
                  padding: EdgeInsets.zero,
                  children: [
                    SizedBox(
                      height: MediaQuery.of(context).size.height -
                          MediaQuery.of(context).padding.vertical,
                      child: WebViewWidget(controller: controller),
                    ),
                  ],
                ),
              ),

              // Floating app-blocker button, bottom-right. The web Lion AI
              // button sits bottom-LEFT at the same height, so the two are
              // balanced on opposite corners, just above the web bottom nav.
              if (!loading)
                Positioned(
                  right: 16,
                  bottom: 112,
                  child: GestureDetector(
                    onTap: _openGuard,
                    child: Container(
                      height: 56,
                      width: 56,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        gradient: const LinearGradient(
                          colors: [Color(0xFFFFB454), Color(0xFFFF9D4D)],
                        ),
                        border: Border.all(color: Colors.white.withValues(alpha: 0.9), width: 2),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withValues(alpha: 0.4),
                            blurRadius: 14,
                            offset: const Offset(0, 4),
                          ),
                        ],
                      ),
                      child: const Center(
                        child: Icon(Icons.shield_moon, color: Color(0xFF241A05), size: 28),
                      ),
                    ),
                  ),
                ),

              if (loading)
                Container(
                  color: const Color(0xFF0B0D14),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Text('🦁', style: TextStyle(fontSize: 64)),
                        const SizedBox(height: 16),
                        SizedBox(
                          width: 160,
                          child: LinearProgressIndicator(
                            value: progress == 0 ? null : progress / 100,
                            backgroundColor: Colors.white12,
                            color: const Color(0xFF6C8CFF),
                          ),
                        ),
                        const SizedBox(height: 12),
                        const Text('FocusLion',
                            style: TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.bold,
                                fontSize: 16)),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

// ============================================================
//  Guard screen — the app blocker (synced with your account)
// ============================================================
class GuardScreen extends StatefulWidget {
  const GuardScreen({super.key});

  @override
  State<GuardScreen> createState() => _GuardScreenState();
}

class _GuardScreenState extends State<GuardScreen> {
  Session? session;
  StreamSubscription<AuthState>? _sub;

  @override
  void initState() {
    super.initState();
    session = db.auth.currentSession;
    _sub = db.auth.onAuthStateChange.listen((data) {
      if (mounted) setState(() => session = data.session);
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('App Guard 🦁', style: TextStyle(fontWeight: FontWeight.bold)),
        backgroundColor: const Color(0xFF0B0D14),
        actions: [
          IconButton(
            tooltip: 'Roar reminders',
            icon: const Icon(Icons.alarm),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const RemindersScreen()),
            ),
          ),
          IconButton(
            tooltip: 'Meet your 3D lion',
            icon: const Text('🦁', style: TextStyle(fontSize: 22)),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const Lion3DScreen()),
            ),
          ),
        ],
      ),
      body: SafeArea(
        child: session == null ? const GuardLoginBody() : const GuardHomeBody(),
      ),
    );
  }
}

// ---------------- Login (fallback if the SSO bridge hasn't run) ----------------
class GuardLoginBody extends StatefulWidget {
  const GuardLoginBody({super.key});

  @override
  State<GuardLoginBody> createState() => _GuardLoginBodyState();
}

class _GuardLoginBodyState extends State<GuardLoginBody> {
  final email = TextEditingController();
  final password = TextEditingController();
  bool busy = false;
  String? error;

  Future<void> login() async {
    setState(() { busy = true; error = null; });
    try {
      await db.auth.signInWithPassword(
        email: email.text.trim(), password: password.text);
    } on AuthException catch (e) {
      setState(() => error = e.message);
    } catch (_) {
      setState(() => error = 'Could not sign in. Check your connection.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('🛡️', style: TextStyle(fontSize: 56)),
            const SizedBox(height: 12),
            const Text('Sign in to set up blocking',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            Text('Tip: if you’re already logged into FocusLion, just go back and reopen this — it signs you in automatically.',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 12.5, color: Colors.white.withValues(alpha: 0.6))),
            const SizedBox(height: 22),
            TextField(
              controller: email,
              keyboardType: TextInputType.emailAddress,
              decoration: const InputDecoration(hintText: 'Email'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: password,
              obscureText: true,
              decoration: const InputDecoration(hintText: 'Password'),
            ),
            if (error != null) ...[
              const SizedBox(height: 12),
              Text(error!, style: const TextStyle(color: Color(0xFFFF8A9B))),
            ],
            const SizedBox(height: 18),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                style: FilledButton.styleFrom(
                  backgroundColor: const Color(0xFFFFB454),
                  foregroundColor: const Color(0xFF241A05),
                  padding: const EdgeInsets.symmetric(vertical: 16),
                ),
                onPressed: busy ? null : login,
                child: Text(busy ? 'Signing in…' : 'Log in & sync'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------- Guarded app model ----------------
class GuardedApp {
  final String pkg;
  final String label;
  final String emoji;
  final bool enabled;
  final bool scheduled;
  final int fromMin;
  final int untilMin;
  final int dailyLimit;

  GuardedApp({
    required this.pkg, required this.label, required this.emoji,
    required this.enabled, required this.scheduled,
    required this.fromMin, required this.untilMin, required this.dailyLimit,
  });

  Map<String, dynamic> toServiceJson() => {
        'pkg': pkg, 'label': label,
        'enabled': enabled,
        'scheduled': scheduled,
        'fromMin': fromMin, 'untilMin': untilMin,
        'dailyLimit': dailyLimit,
      };
}

/// Builds the guarded-app list from raw social_limits rows, keeping only the
/// apps we know an Android package for. Shared by the Guard screen and the
/// app-wide push below so both produce identical config.
List<GuardedApp> guardedAppsFromRows(List rows) {
  final list = <GuardedApp>[];
  for (final r in rows) {
    final name = r['app_name'] as String;
    final pkg = appPackages[name];
    if (pkg == null) continue;
    list.add(GuardedApp(
      pkg: pkg,
      label: name,
      emoji: appEmojis[name] ?? '📱',
      enabled: r['enabled'] == true,
      scheduled: r['schedule_enabled'] == true,
      fromMin: r['allowed_from_min'] ?? 1080,
      untilMin: r['allowed_until_min'] ?? 1200,
      dailyLimit: r['daily_limit_min'] ?? 30,
    ));
  }
  list.sort((a, b) => a.label.compareTo(b.label));
  return list;
}

/// Fetches the latest limits and pushes them to the native blocker right away,
/// so a change made in the web app (e.g. new allowed hours on the Wellbeing
/// page) takes effect immediately — without opening the Guard screen or
/// pulling to refresh. Safe to call anytime; it's a no-op when signed out.
Future<void> pushGuardConfig() async {
  try {
    final uid = db.auth.currentUser?.id;
    if (uid == null) return;
    final rows = await db.from('social_limits').select().eq('user_id', uid);
    final apps = guardedAppsFromRows(rows as List);
    final json = jsonEncode(apps.map((a) => a.toServiceJson()).toList());
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('apps_cache', json);
    try {
      await channel.invokeMethod('setConfig', {'json': json});
    } catch (_) {}
  } catch (_) {}
}

// ---------------- Guard home ----------------
class GuardHomeBody extends StatefulWidget {
  const GuardHomeBody({super.key});

  @override
  State<GuardHomeBody> createState() => _GuardHomeBodyState();
}

class _GuardHomeBodyState extends State<GuardHomeBody> with WidgetsBindingObserver {
  List<GuardedApp> apps = [];
  Map<String, int> usedMin = {};
  Timer? _usageTimer;
  bool usage = false, overlay = false, battery = false, running = false;
  bool loading = true;
  String? syncError;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _init();
    _usageTimer = Timer.periodic(
        const Duration(seconds: 10), (_) => _refreshStatus());
  }

  @override
  void dispose() {
    _usageTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshStatus();
      _sync();
    }
  }

  Future<void> _init() async {
    await _refreshStatus();
    await _sync();
  }

  Future<void> _refreshStatus() async {
    try {
      final s = await channel.invokeMapMethod<String, dynamic>('status');
      if (s != null && mounted) {
        setState(() {
          usage = s['usage'] == true;
          overlay = s['overlay'] == true;
          battery = s['battery'] == true;
          running = s['running'] == true;
        });
      }
    } catch (_) {}
    try {
      final raw = await channel.invokeMethod<String>('usage');
      if (raw != null && mounted) {
        final m = jsonDecode(raw) as Map<String, dynamic>;
        setState(() => usedMin =
            m.map((k, v) => MapEntry(k, (v as num).toInt() ~/ 60000)));
      }
    } catch (_) {}
  }

  Future<void> _sync() async {
    try {
      final uid = db.auth.currentUser?.id;
      if (uid == null) return;
      final rows = await db.from('social_limits').select().eq('user_id', uid);
      final list = guardedAppsFromRows(rows as List);
      if (mounted) setState(() { apps = list; syncError = null; loading = false; });
      await _applyConfig();
    } catch (e) {
      if (mounted) setState(() { syncError = 'Sync failed — pull to retry.'; loading = false; });
    }
  }

  String get _configJson =>
      jsonEncode(apps.map((a) => a.toServiceJson()).toList());

  Future<void> _applyConfig() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('apps_cache', _configJson);
    try {
      await channel.invokeMethod('setConfig', {'json': _configJson});
    } catch (_) {}
  }

  Future<void> _toggleGuard(bool on) async {
    if (on && (!usage || !overlay)) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Grant both permissions first (steps 1 and 2)')));
      return;
    }
    try {
      if (on) {
        await channel.invokeMethod('requestNotif');
        await channel.invokeMethod('start', {'json': _configJson});
      } else {
        await channel.invokeMethod('stop');
      }
    } catch (_) {}
    await _refreshStatus();
  }

  String _t(int m) {
    final h = m ~/ 60, mm = m % 60;
    final ampm = h >= 12 ? 'PM' : 'AM';
    final hh = h % 12 == 0 ? 12 : h % 12;
    return '$hh:${mm.toString().padLeft(2, '0')} $ampm';
  }

  @override
  Widget build(BuildContext context) {
    final permsOk = usage && overlay;
    final scheduledApps = apps.where((a) => a.enabled && a.scheduled).toList();
    final minuteOnly = apps.where((a) => a.enabled && !a.scheduled).toList();
    final guardedCount = scheduledApps.length + minuteOnly.length;

    return RefreshIndicator(
      onRefresh: _sync,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
        children: [
          Text('Synced with ${db.auth.currentUser?.email ?? "your account"}',
              style: TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: 0.5))),
          const SizedBox(height: 16),

          Card(
            color: running ? const Color(0xFF173321) : const Color(0xFF2A1A10),
            child: SwitchListTile(
              value: running,
              onChanged: _toggleGuard,
              title: Text(running ? 'Guard is ACTIVE 🟢' : 'Guard is off',
                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 17)),
              subtitle: Text(
                running
                    ? 'Guarding $guardedCount app(s) — by allowed hours and daily limits, even when this app is closed.'
                    : permsOk
                        ? 'Flip on to start guarding your synced apps.'
                        : 'Complete the permission steps below, then turn me on.',
                style: TextStyle(fontSize: 12.5, color: Colors.white.withValues(alpha: 0.65)),
              ),
              secondary: Text(running ? '🦁' : '😴', style: const TextStyle(fontSize: 26)),
            ),
          ),
          const SizedBox(height: 14),

          if (!permsOk || !battery) ...[
            _label('SETUP — ONE TIME'),
            _PermTile(done: usage, step: '1', title: 'Usage access',
                subtitle: 'Lets the lion see which app is open.',
                onTap: () => channel.invokeMethod('openUsage')),
            _PermTile(done: overlay, step: '2', title: 'Display over other apps',
                subtitle: 'Lets the lion roar on top of blocked apps.',
                onTap: () => channel.invokeMethod('openOverlay')),
            _PermTile(done: battery, step: '3', title: 'Ignore battery optimization',
                subtitle: 'Keeps the guard alive in the background.',
                onTap: () => channel.invokeMethod('openBattery')),
            const SizedBox(height: 14),
          ],

          _label('GUARDED APPS (FROM YOUR SETTINGS)'),
          if (loading)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: CircularProgressIndicator()),
            )
          else if (syncError != null)
            Card(child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(syncError!, style: const TextStyle(color: Color(0xFFFF8A9B))))),

          if (!loading && scheduledApps.isEmpty && minuteOnly.isEmpty)
            Card(child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(children: [
                const Text('😴', style: TextStyle(fontSize: 40)),
                const SizedBox(height: 8),
                Text('No apps to guard yet.\nOpen FocusLion → Wellbeing → enable an app and turn on "Allowed hours only", then pull down here to sync.',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 13, color: Colors.white.withValues(alpha: 0.6))),
              ]))),

          ...scheduledApps.map((a) => Card(
                margin: const EdgeInsets.only(bottom: 10),
                child: ListTile(
                  leading: Text(a.emoji, style: const TextStyle(fontSize: 24)),
                  title: Text(a.label, style: const TextStyle(fontWeight: FontWeight.bold)),
                  subtitle: Text(
                      'Allowed only ${_t(a.fromMin)} – ${_t(a.untilMin)}'
                      '${a.dailyLimit > 0 ? '\nUsed ${usedMin[a.pkg] ?? 0}m of ${a.dailyLimit}m today' : ''}',
                      style: const TextStyle(color: Color(0xFFFFB454), fontSize: 12.5)),
                  trailing: const Icon(Icons.lock_clock, color: Color(0xFFFFB454)),
                ),
              )),

          if (minuteOnly.isNotEmpty) ...[
            const SizedBox(height: 4),
            ...minuteOnly.map((a) => Card(
                  margin: const EdgeInsets.only(bottom: 10),
                  child: ListTile(
                    leading: Text(a.emoji, style: const TextStyle(fontSize: 24)),
                    title: Text(a.label, style: const TextStyle(fontWeight: FontWeight.bold)),
                    subtitle: Text(
                        'Used ${usedMin[a.pkg] ?? 0}m of ${a.dailyLimit}m today — blocks at the limit',
                        style: const TextStyle(color: Color(0xFFFFB454), fontSize: 12.5)),
                    trailing: const Icon(Icons.timelapse, color: Color(0xFFFFB454)),
                  ),
                )),
          ],

          const SizedBox(height: 10),
          Text('Tip: change which apps and hours are guarded in FocusLion → Wellbeing. Pull down to re-sync anytime; it also syncs automatically when you open this screen.',
              style: TextStyle(fontSize: 12, height: 1.5, color: Colors.white.withValues(alpha: 0.45))),
        ],
      ),
    );
  }

  Widget _label(String t) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(t, style: TextStyle(
            fontSize: 11, letterSpacing: 1.4, fontWeight: FontWeight.bold,
            color: Colors.white.withValues(alpha: 0.4))),
      );
}

class _PermTile extends StatelessWidget {
  const _PermTile({required this.done, required this.step, required this.title,
    required this.subtitle, required this.onTap});

  final bool done;
  final String step;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        onTap: done ? null : onTap,
        leading: CircleAvatar(
          radius: 16,
          backgroundColor: done ? const Color(0xFF1F4D32) : const Color(0xFF3A2E14),
          child: done
              ? const Icon(Icons.check, size: 18, color: Color(0xFF6EE7A0))
              : Text(step, style: const TextStyle(fontWeight: FontWeight.bold, color: Color(0xFFFFB454))),
        ),
        title: Text(title, style: TextStyle(
            fontWeight: FontWeight.w600, fontSize: 14.5,
            decoration: done ? TextDecoration.lineThrough : null,
            color: done ? Colors.white.withValues(alpha: 0.45) : Colors.white)),
        subtitle: done ? null : Text(subtitle,
            style: TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: 0.55))),
        trailing: done ? null : const Icon(Icons.chevron_right),
      ),
    );
  }
}
