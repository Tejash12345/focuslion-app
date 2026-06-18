import 'dart:async';
import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
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

// this device's FCM token, cached so we can (re)save it to Supabase whenever a
// session becomes available
String? _fcmToken;

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
    await Supabase.initialize(url: supabaseUrl, publishableKey: supabaseAnonKey);
  } catch (_) {}
  await _initNotifications();
  try {
    await Firebase.initializeApp();
    await _initFirebaseMessaging();
  } catch (e) {
    debugPrint('Firebase init failed: $e');
  }
  runApp(const FocusLionApp());
}

SupabaseClient get db => Supabase.instance.client;

Future<void> _initNotifications() async {
  try {
    const androidInit = AndroidInitializationSettings('ic_stat_notification');
    await _notifs.initialize(const InitializationSettings(android: androidInit));
    final android = _notifs
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();
    await android?.createNotificationChannel(const AndroidNotificationChannel(
      _notifChannel, 'FocusLion',
      description: 'Reminders and feed activity',
      importance: Importance.high,
    ));
    // permission is requested through a friendly in-app prompt on first launch
    // (see _maybePromptNotifications), not silently here
  } catch (_) {}
}

/// Shows an Android notification. Called from the web app via the FLNotify
/// JavaScript channel (reminders, new likes/comments on your posts, etc.).
Future<void> showAppNotification(String raw) async {
  try {
    final data = jsonDecode(raw);
    final title = (data is Map && data['title'] != null) ? '${data['title']}' : 'FocusLion';
    final body = (data is Map && data['body'] != null) ? '${data['body']}' : '';
    final tag = (data is Map) ? data['tag'] : null;
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

  // user tapped the notification while the app was backgrounded (not killed)
  FirebaseMessaging.onMessageOpenedApp.listen((m) {
    debugPrint('FCM tapped (background): ${m.messageId} data=${m.data}');
  });

  // user tapped the notification that launched the app from a killed state
  final initial = await fm.getInitialMessage();
  if (initial != null) {
    debugPrint('FCM tapped (terminated): ${initial.messageId} data=${initial.data}');
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
    if (state.session != null) _saveFcmToken();
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
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    controller = WebViewController(
      // voice messages: when the site asks for the microphone, request the
      // native permission and pass the grant through to the page
      onPermissionRequest: (request) async {
        if (request.types.contains(WebViewPermissionResourceType.microphone)) {
          final status = await Permission.microphone.request();
          if (status.isGranted) {
            await request.grant();
          } else {
            await request.deny();
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
      // the web app pings here whenever blocking limits change, so the native
      // Guard re-syncs and enforces the new caps/hours immediately
      ..addJavaScriptChannel('FLGuard', onMessageReceived: (_) => pushGuardConfig())
      ..setBackgroundColor(const Color(0xFF0B0D14))
      ..setNavigationDelegate(
        NavigationDelegate(
          onProgress: (p) => setState(() => progress = p),
          onPageStarted: (_) => setState(() => loading = true),
          onPageFinished: (_) {
            setState(() => loading = false);
            _grabSession();
            _checkForUpdate();
            _pushUsageToWeb();
            if (!_notifPrompted) {
              _notifPrompted = true;
              _maybePromptNotifications();
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

  Future<void> _onAuthToken(String raw) async {
    if (raw.isEmpty) return;
    try {
      final data = jsonDecode(raw);
      final refresh = (data is Map) ? data['refresh_token'] : null;
      if (refresh is String && refresh.isNotEmpty && db.auth.currentSession == null) {
        await db.auth.setSession(refresh);
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
