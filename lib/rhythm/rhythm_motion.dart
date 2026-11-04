import 'dart:developer' show Timeline;

import 'package:flutter/material.dart';

/// FrameTiming uses the engine's monotonic clock, not wall-clock time.
/// Keep boundaries so delayed timing callbacks retain the phase of each frame.
class RhythmFramePhases {
  final _boundaries = <({int time, String phase})>[];

  void mark(String phase, {int? timestamp}) {
    if (_boundaries.isNotEmpty && _boundaries.last.phase == phase) return;
    _boundaries.add((time: timestamp ?? Timeline.now, phase: phase));
    if (_boundaries.length > 128) _boundaries.removeAt(0);
  }

  String at(int timestamp) {
    for (final boundary in _boundaries.reversed) {
      if (boundary.time <= timestamp) return boundary.phase;
    }
    return 'startup';
  }
}

/// Animate only the incoming surface; never retain two scrollables sharing a
/// controller or two copies of the photo Heroes during an album switch.
class RhythmSurfaceTransition extends StatefulWidget {
  const new({super.key, required this.identity, required this.child});
  final Object identity;
  final Widget child;

  @override
  State<RhythmSurfaceTransition> createState() => _RhythmSurfaceTransitionState();
}

class RhythmViewerChrome extends StatelessWidget {
  const new({super.key, required this.visible, required this.top, required this.child});
  final bool visible, top;
  final Widget child;

  @override
  Widget build(BuildContext context) => IgnorePointer(
    ignoring: !visible,
    child: AnimatedSwitcher(
      duration: MediaQuery.disableAnimationsOf(context) ? Duration.zero : const Duration(milliseconds: 160),
      transitionBuilder: (child, animation) => FadeTransition(
        opacity: animation,
        child: SlideTransition(
          position: Tween(begin: Offset(0, top ? -.15 : .15), end: Offset.zero).animate(animation),
          child: child,
        ),
      ),
      child: visible ? RepaintBoundary(child: child) : const SizedBox(width: double.infinity),
    ),
  );
}

class _RhythmSurfaceTransitionState extends State<RhythmSurfaceTransition> with SingleTickerProviderStateMixin {
  late final controller = AnimationController(vsync: this, duration: const Duration(milliseconds: 180), value: 1);
  late final curve = CurvedAnimation(parent: controller, curve: Curves.easeOutCubic);

  @override
  void didUpdateWidget(covariant RhythmSurfaceTransition oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.identity != widget.identity) {
      if (MediaQuery.disableAnimationsOf(context)) {
        controller.value = 1;
      } else {
        controller.forward(from: 0);
      }
    }
  }

  @override
  void dispose() {
    curve.dispose();
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => SlideTransition(
    position: Tween(begin: const Offset(0, .025), end: Offset.zero).animate(curve),
    child: FadeTransition(
      opacity: curve,
      child: RepaintBoundary(child: widget.child),
    ),
  );
}
