import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Lets the user set times of day when the animated lion automatically pops up
/// with a roar (handled natively by ReminderReceiver, even when the app is
/// closed). Times are stored locally and pushed to the native scheduler.
class RemindersScreen extends StatefulWidget {
  const RemindersScreen({super.key});

  @override
  State<RemindersScreen> createState() => _RemindersScreenState();
}

class _RemindersScreenState extends State<RemindersScreen> {
  static const _channel = MethodChannel('focuslion/guard');
  static const _prefsKey = 'roar_reminders';

  // each item: {'h': int, 'm': int, 'label': String}
  List<Map<String, dynamic>> _reminders = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_prefsKey) ?? '[]';
    try {
      final list = (jsonDecode(raw) as List).cast<Map<String, dynamic>>();
      list.sort((a, b) =>
          (a['h'] * 60 + a['m']).compareTo(b['h'] * 60 + b['m']));
      if (mounted) setState(() => _reminders = list);
    } catch (_) {}
    if (mounted) setState(() => _loading = false);
    // make sure native has the current set scheduled
    _push();
  }

  Future<void> _persist() async {
    _reminders.sort(
        (a, b) => (a['h'] * 60 + a['m']).compareTo(b['h'] * 60 + b['m']));
    final json = jsonEncode(_reminders);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKey, json);
    _push(json);
    if (mounted) setState(() {});
  }

  Future<void> _push([String? json]) async {
    try {
      await _channel.invokeMethod(
          'setReminders', {'json': json ?? jsonEncode(_reminders)});
    } catch (_) {}
  }

  Future<void> _add() async {
    final picked = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.now(),
      helpText: 'Roar reminder time',
    );
    if (picked == null) return;
    // avoid duplicates at the same minute
    final exists = _reminders
        .any((r) => r['h'] == picked.hour && r['m'] == picked.minute);
    if (exists) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('You already have a reminder then.')));
      }
      return;
    }
    _reminders.add({'h': picked.hour, 'm': picked.minute, 'label': ''});
    await _persist();
  }

  Future<void> _remove(int i) async {
    _reminders.removeAt(i);
    await _persist();
  }

  String _fmt(int h, int m) {
    final ampm = h >= 12 ? 'PM' : 'AM';
    final hh = h % 12 == 0 ? 12 : h % 12;
    return '$hh:${m.toString().padLeft(2, '0')} $ampm';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0B0D14),
      appBar: AppBar(
        title: const Text('Roar reminders 🦁',
            style: TextStyle(fontWeight: FontWeight.bold)),
        backgroundColor: const Color(0xFF0B0D14),
      ),
      floatingActionButton: FloatingActionButton.extended(
        backgroundColor: const Color(0xFFFFB454),
        foregroundColor: const Color(0xFF241A05),
        onPressed: _add,
        icon: const Icon(Icons.add_alarm),
        label: const Text('Add time', style: TextStyle(fontWeight: FontWeight.bold)),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 100),
              children: [
                Text(
                  'At each time, the lion springs up with a roar — even when the '
                  'app is closed — to pull you back to focus.',
                  style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.6), height: 1.4),
                ),
                const SizedBox(height: 18),
                if (_reminders.isEmpty)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(children: [
                        const Text('🦁', style: TextStyle(fontSize: 44)),
                        const SizedBox(height: 10),
                        Text('No reminders yet.\nTap “Add time” to set one.',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                                color: Colors.white.withValues(alpha: 0.6))),
                      ]),
                    ),
                  )
                else
                  ..._reminders.asMap().entries.map((e) {
                    final r = e.value;
                    return Card(
                      margin: const EdgeInsets.only(bottom: 10),
                      child: ListTile(
                        leading: const Text('⏰', style: TextStyle(fontSize: 22)),
                        title: Text(_fmt(r['h'] as int, r['m'] as int),
                            style: const TextStyle(
                                fontWeight: FontWeight.bold, fontSize: 17)),
                        subtitle: const Text('Daily • lion roars',
                            style: TextStyle(color: Color(0xFFFFB454), fontSize: 12.5)),
                        trailing: IconButton(
                          icon: const Icon(Icons.delete_outline,
                              color: Color(0xFFFF8A9B)),
                          onPressed: () => _remove(e.key),
                        ),
                      ),
                    );
                  }),
                const SizedBox(height: 16),
                Text(
                  'Note: needs the “Display over other apps” permission (same as '
                  'the guard). Times may vary by a few minutes to save battery.',
                  style: TextStyle(
                      fontSize: 12,
                      height: 1.4,
                      color: Colors.white.withValues(alpha: 0.4)),
                ),
              ],
            ),
    );
  }
}
