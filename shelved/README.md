# Shelved: in-app live camera

`CameraScreen.kt` is an in-app CameraX viewfinder that brackets QR codes live (ML Kit) and snaps
photos for circle search. It was pulled from the build on 2026-11-18; Snap uses the system camera again.

To bring it back:
- Move `CameraScreen.kt` into `app/src/main/java/com/lucrativeworm/seekframe/ui/screens/`.
- Add `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-view`, `camera-mlkit-vision` (1.6.2).
- Add `android.permission.CAMERA` (and `uses-feature android.hardware.camera`, required=false) to the manifest.
- Add a `Snapping` destination in `MainActivity` that shows `CameraScreen`, and point `onSnap` at it,
  keeping the system camera as `onSystemCamera` fallback.
