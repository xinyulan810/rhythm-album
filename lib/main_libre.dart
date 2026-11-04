import 'package:aves/app_flavor.dart';
import 'package:aves/main_common.dart';

const AppFlavor _flavor = .libre;

@pragma('vm:entry-point')
void main() => mainCommon(_flavor);
