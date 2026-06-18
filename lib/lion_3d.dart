import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:webview_flutter/webview_flutter.dart';

/// Your 3D lion mascot — "Lowpoly Male Lion Rigged Animated for VR AR" by
/// Dzung Dinh (via Sketchfab), shown live through Sketchfab's Viewer API.
///
/// This model is rigged and animated: its 10 moves are baked into one timeline
/// as frame ranges, so each button seeks to that range and plays it (looping
/// for idle/walk/run, one-shot for the rest). The ROAR button also fires the
/// loud roar sound + a haptic. Drag/pinch orbits and zooms the real 3D model.
class Lion3DScreen extends StatefulWidget {
  const Lion3DScreen({super.key});

  @override
  State<Lion3DScreen> createState() => _Lion3DScreenState();
}

/// A move = a frame range in the model's single animation take.
class _Move {
  const _Move(this.label, this.icon, this.from, this.to, {this.loop = false});
  final String label;
  final IconData icon;
  final int from;
  final int to;
  final bool loop;
}

class _Lion3DScreenState extends State<Lion3DScreen> {
  static const _channel = MethodChannel('focuslion/guard');
  static const _modelUid = 'c87e400e549f40f39a22dff7bf256d34';

  // Frame ranges from the model's description (10 animations on one timeline).
  static const _totalFrames = 500;
  static const _moves = <_Move>[
    _Move('Idle', Icons.self_improvement, 5, 64, loop: true),
    _Move('Walk', Icons.directions_walk, 70, 99, loop: true),
    _Move('Run', Icons.directions_run, 105, 124, loop: true),
    _Move('Jump', Icons.arrow_upward, 130, 159),
    _Move('Pounce', Icons.flash_on, 165, 194),
    _Move('Bite', Icons.set_meal, 200, 224),
    _Move('Claws', Icons.back_hand, 285, 324),
    _Move('Hit', Icons.report, 330, 349),
    _Move('Sleep', Icons.bedtime, 355, 500),
  ];
  // roar is its own highlighted button
  static const _roarFrom = 230, _roarTo = 279;

  late final WebViewController _web;
  bool _ready = false;
  bool _error = false;
  String _status = 'Summoning the lion…';
  String _current = '';
  Timer? _loadTimeout;

  @override
  void initState() {
    super.initState();
    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFF0B0D14))
      ..addJavaScriptChannel('LionChannel', onMessageReceived: _onMessage)
      ..loadHtmlString(_html, baseUrl: 'https://sketchfab.com/');

    _loadTimeout = Timer(const Duration(seconds: 20), () {
      if (mounted && !_ready) setState(() => _error = true);
    });
  }

  @override
  void dispose() {
    _loadTimeout?.cancel();
    super.dispose();
  }

  void _onMessage(JavaScriptMessage m) {
    if (!mounted) return;
    switch (m.message) {
      case 'ready':
        _loadTimeout?.cancel();
        setState(() {
          _ready = true;
          _error = false;
          _current = 'Auto';
          _status = 'Auto-playing all moves 🦁';
        });
        break;
      case 'noanim':
        _loadTimeout?.cancel();
        setState(() {
          _ready = true;
          _status = 'Loaded, but no animation clips were found.';
        });
        break;
      case 'error':
        setState(() => _error = true);
        break;
    }
  }

  void _js(String code) => _web.runJavaScript(code).catchError((_) {});

  void _auto() {
    _js('window.lionAuto()');
    setState(() {
      _current = 'Auto';
      _status = 'Auto-playing all moves 🦁';
    });
  }

  void _play(_Move m) {
    _js('window.lionSeg(${m.from}, ${m.to}, ${m.loop})');
    setState(() {
      _current = m.label;
      _status = m.loop ? '${m.label}…' : '${m.label}!';
    });
  }

  Future<void> _roar() async {
    try {
      await _channel.invokeMethod('roar');
    } catch (_) {}
    HapticFeedback.heavyImpact();
    _js('window.lionSeg($_roarFrom, $_roarTo, false)');
    if (mounted) {
      setState(() {
        _current = 'Roar';
        _status = 'ROAAAR! 🦁';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0B0D14),
      appBar: AppBar(
        title: const Text('Your Lion 🦁',
            style: TextStyle(fontWeight: FontWeight.bold)),
        backgroundColor: const Color(0xFF0B0D14),
      ),
      body: Column(
        children: [
          Expanded(
            child: Stack(
              children: [
                WebViewWidget(controller: _web),
                if (!_ready) _overlay(),
              ],
            ),
          ),
          _controls(),
        ],
      ),
    );
  }

  Widget _overlay() {
    return Container(
      color: const Color(0xFF0B0D14),
      padding: const EdgeInsets.all(28),
      alignment: Alignment.center,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text('🦁', style: TextStyle(fontSize: 90)),
          const SizedBox(height: 16),
          if (!_error) ...[
            const SizedBox(
              width: 150,
              child: LinearProgressIndicator(
                backgroundColor: Colors.white12,
                color: Color(0xFFFFB454),
              ),
            ),
            const SizedBox(height: 14),
            const Text('Summoning the lion…',
                style: TextStyle(color: Colors.white70)),
          ] else ...[
            const Text("Couldn't load the 3D lion",
                style: TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.bold)),
            const SizedBox(height: 10),
            Text(
              'The 3D lion streams from Sketchfab, so it needs an internet '
              'connection. Check your connection and reopen this screen.\n\n'
              'The ROAR button below still works!',
              textAlign: TextAlign.center,
              style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.6), height: 1.5),
            ),
          ],
        ],
      ),
    );
  }

  Widget _controls() {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 22),
      decoration: const BoxDecoration(
        color: Color(0xFF11141F),
        borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(_status,
              style: const TextStyle(
                  color: Color(0xFFFFB454), fontWeight: FontWeight.w600)),
          const SizedBox(height: 14),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            alignment: WrapAlignment.center,
            children: [
              _autoChip(),
              for (final m in _moves) _chip(m),
            ],
          ),
          const SizedBox(height: 12),
          _roarButton(),
          const SizedBox(height: 10),
          Text('Drag to turn • pinch to zoom',
              style: TextStyle(
                  fontSize: 12, color: Colors.white.withValues(alpha: 0.4))),
          const SizedBox(height: 4),
          Text('Lowpoly Male Lion (Rigged) — Dzung Dinh · Sketchfab',
              textAlign: TextAlign.center,
              style: TextStyle(
                  fontSize: 10.5, color: Colors.white.withValues(alpha: 0.3))),
        ],
      ),
    );
  }

  Widget _autoChip() {
    final selected = _current == 'Auto';
    final enabled = _ready;
    return ActionChip(
      avatar: Icon(Icons.all_inclusive,
          size: 17,
          color: !enabled
              ? Colors.white38
              : (selected ? const Color(0xFF241A05) : Colors.white)),
      label: Text('Auto · all',
          style: TextStyle(
              fontWeight: FontWeight.bold,
              color: !enabled
                  ? Colors.white38
                  : (selected ? const Color(0xFF241A05) : Colors.white))),
      backgroundColor:
          selected ? const Color(0xFFFFB454) : const Color(0xFF1E2333),
      onPressed: enabled ? _auto : null,
    );
  }

  Widget _chip(_Move m) {
    final selected = _current == m.label;
    final enabled = _ready;
    return ActionChip(
      avatar: Icon(m.icon,
          size: 17,
          color: !enabled
              ? Colors.white38
              : (selected ? const Color(0xFF241A05) : Colors.white)),
      label: Text(m.label,
          style: TextStyle(
              color: !enabled
                  ? Colors.white38
                  : (selected ? const Color(0xFF241A05) : Colors.white))),
      backgroundColor:
          selected ? const Color(0xFFFFB454) : const Color(0xFF1E2333),
      onPressed: enabled ? () => _play(m) : null,
    );
  }

  Widget _roarButton() {
    return FilledButton.icon(
      style: FilledButton.styleFrom(
        backgroundColor: const Color(0xFFFFB454),
        foregroundColor: const Color(0xFF241A05),
        padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 12),
      ),
      onPressed: _roar,
      icon: const Text('🦁', style: TextStyle(fontSize: 20)),
      label: const Text('ROAR',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
    );
  }

  // Self-contained page: Sketchfab Viewer API + frame-range animation control.
  String get _html => '''
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
  html,body{margin:0;padding:0;height:100%;background:#0B0D14;overflow:hidden}
  #api-frame{width:100%;height:100%;border:0;display:block}
</style>
<script src="https://static.sketchfab.com/api/sketchfab-viewer-1.12.1.js"></script>
</head>
<body>
<iframe id="api-frame" allow="autoplay;fullscreen;xr-spatial-tracking" allowfullscreen></iframe>
<script>
  var iframe = document.getElementById('api-frame');
  var client = new Sketchfab('1.12.1', iframe);
  var api = null, animReady = false, animDur = 0;
  var segTimer = null, segStart = 0, segEnd = 0, onEnd = null;
  var TF = $_totalFrames;
  // every move as [startFrame, endFrame], played one after another in Auto mode
  var SEQ = [[5,64],[70,99],[105,124],[130,159],[165,194],[200,224],[230,279],[285,324],[330,349],[355,500]];
  var seqIdx = 0, autoMode = false;

  function send(m){ try { LionChannel.postMessage(m); } catch(e){} }
  function clearSeg(){ if (segTimer){ clearInterval(segTimer); segTimer = null; } onEnd = null; }

  // Play frames [sf, ef] once; call done() when it finishes.
  function runSeg(sf, ef, done){
    if (!api || !animReady) return;
    clearSeg();
    segStart = (sf / TF) * animDur;
    segEnd   = (ef / TF) * animDur;
    onEnd = done;
    api.setSpeed(1);
    api.seekTo(segStart);
    api.play();
    segTimer = setInterval(function(){
      api.getCurrentTime(function(err, t){
        if (err || t == null) return;
        if (t >= segEnd - 0.02 || t < segStart - 0.4){
          var cb = onEnd;
          clearSeg();
          if (cb) cb();
        }
      });
    }, 60);
  }

  // Manual: play one move (loop it if asked). Stops Auto mode.
  window.lionSeg = function(sf, ef, loop){
    autoMode = false;
    function again(){ runSeg(sf, ef, loop ? again : null); }
    again();
  };

  // Auto: cycle through ALL moves forever.
  window.lionAuto = function(){
    autoMode = true;
    seqIdx = 0;
    function step(){
      if (!autoMode) return;
      var s = SEQ[seqIdx];
      seqIdx = (seqIdx + 1) % SEQ.length;
      runSeg(s[0], s[1], step);
    }
    step();
  };

  client.init('$_modelUid', {
    success: function(a){
      api = a;
      api.start();
      api.addEventListener('viewerready', function(){
        window.__api = api;
        try { api.setCycleMode('loopOne'); } catch(e){}
        // Gentle zoom-out only (distance scale, no pan) to make the lion a bit smaller.
        api.getCameraLookAt(function(err, c){
          if (err || !c) return;
          var p = c.position, t = c.target, f = 0.95;
          api.setCameraLookAt(
            [t[0]+(p[0]-t[0])*f, t[1]+(p[1]-t[1])*f, t[2]+(p[2]-t[2])*f], t, 0);
        });
        api.getAnimations(function(err, anims){
          if (!err && anims && anims.length){
            var uid = anims[0][0];
            animDur = anims[0][2] || 0;
            api.setCurrentAnimationByUID(uid, function(){
              animReady = animDur > 0;
              if (animReady){ window.lionAuto(); send('ready'); }
              else { send('noanim'); }
            });
          } else {
            send('noanim');
          }
        });
      });
    },
    error: function(){ send('error'); },
    autostart: 1, autospin: 0, ui_infos: 0, ui_controls: 1, ui_stop: 0,
    ui_watermark: 1, ui_ar: 0, ui_help: 0, ui_settings: 0, ui_vr: 0,
    ui_fullscreen: 0, ui_annotations: 0, ui_hint: 0, transparent: 0
  });
</script>
</body>
</html>
''';
}
