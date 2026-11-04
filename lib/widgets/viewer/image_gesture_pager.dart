import 'package:aves/widgets/common/behaviour/springy_scroll_physics.dart';
import 'package:aves_magnifier/aves_magnifier.dart';
import 'package:flutter/material.dart';

/// Data-independent form of ViewerVerticalPageView's image/transition/info
/// pages. Shares Aves' gesture arena, spring and background opacity behavior.
class AvesImageGesturePager extends StatefulWidget {
  const new({super.key, required this.image, required this.details, required this.onLeave, required this.controller, this.onMotion});
  final Widget image, details;
  final VoidCallback onLeave;
  final PageController controller;
  final ValueChanged<bool>? onMotion;

  @override
  State<AvesImageGesturePager> createState() => _AvesImageGesturePagerState();
}

class _AvesImageGesturePagerState extends State<AvesImageGesturePager> {
  bool leaving = false;
  static final spring = SpringDescription.withDampingRatio(mass: .5, stiffness: 140, ratio: 1);

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, child) {
      final page = widget.controller.hasClients && widget.controller.position.hasContentDimensions ? widget.controller.page ?? 1 : 1.0;
      final opacity = page.clamp(0.0, 1.0);
      return ColoredBox(
        color: Colors.black.withValues(alpha: opacity * opacity),
        child: child,
      );
    },
    child: NotificationListener<ScrollNotification>(
      onNotification: (event) {
        if (event.depth == 0) {
          if (event is ScrollStartNotification) widget.onMotion?.call(true);
          if (event is ScrollEndNotification) widget.onMotion?.call(false);
        }
        return false;
      },
      child: PageView(
        key: const Key('rhythm-vertical-pager'),
        controller: widget.controller,
        scrollDirection: Axis.vertical,
        physics: MagnifierScrollerPhysics(
          gestureSettings: MediaQuery.gestureSettingsOf(context),
          parent: SpringyScrollPhysics(spring: spring),
        ),
        onPageChanged: (page) {
          if (page == 0 && !leaving) {
            leaving = true;
            widget.onLeave();
          }
        },
        children: [
          const SizedBox(),
          widget.image,
          widget.details,
        ],
      ),
    ),
  );
}
