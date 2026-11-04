# cf https://developer.android.com/topic/performance/app-optimization/add-keep-rules
-keep class deckers.thibault.aves.rhythm.RhythmNative { *; }
# ML Kit discovers these through manifest metadata and a no-argument constructor.
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }

# e.g. com.drew.metadata.exif.ExifSubIFDDirectory
-keep class com.drew.metadata.**{ *; }

-keep class org.beyka.tiffbitmapfactory.**{ *; }

-keep class org.mp4parser.**{ *; }

# referenced from: com.google.crypto.tink
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
