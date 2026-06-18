# FocusLion — Android app

Flutter WebView wrapper for [FocusLion](https://new-app-ruddy-nine.vercel.app)
with a native app-blocker guard and **Firebase Cloud Messaging push notifications**
(chat, likes, comments, reposts, friend requests, AI briefings, announcements).

## 📲 Install (no build needed)

1. On your Android phone, open **[`focuslion-app-release.apk`](./focuslion-app-release.apk)**
   in this repo and tap **Download** (the raw file).
2. Open the downloaded file. If prompted, allow **"Install unknown apps"** for your
   browser / file manager.
3. Tap **Install**, then open **FocusLion** and sign in.
4. Accept the **notification permission** prompt so pushes can arrive.

> The release APK is signed with debug keys (fine for sideloading/testing). For a
> Play Store release you'll need a proper upload key.

## 🔔 Push notifications

The device registers its FCM token to Supabase (`user_push_tokens`) on login.
Server-side triggers + the `fcm-send` Edge Function (in the web repo under
`supabase/`) deliver pushes for social events even when the app is closed.

## 🛠 Build from source

```bash
flutter pub get
flutter build apk --release
# output: build/app/outputs/flutter-apk/app-release.apk
```

Requires the Flutter SDK and Android SDK. `android/local.properties` is generated
automatically on first build.
