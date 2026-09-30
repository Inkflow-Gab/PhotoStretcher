# Photo Stretcher

Stretch one horizontal band of a photo, leave the rest of the picture untouched.

This is **not** a resize tool. You mark a band with two lines, the band gets taller (or
shorter), and everything above and below it keeps its exact size and content. The result is
saved as a new file in `Pictures/PhotoStretcher`. The original is never modified.

- Android 8.0 (API 26) and newer
- Dark, minimal, no ads, no login, no internet, no analytics, works fully offline
- Uses the modern system photo picker, so picking a photo needs **no permission at all**
- Exports at the full original resolution when the device has the memory for it, and says so
  when it has to scale down instead

---

## What it does

1. **Pick Photo** opens the system photo picker.
2. **Tap the photo once** to place the first line, **tap again** for the second. Whichever is
   higher is always the top line, no matter which one you placed first.
3. **Drag either line** by its round handle (44dp touch target). Lines cannot cross and cannot
   leave the picture. Dragging inside the band moves the whole band.
4. **Move the slider** from 100% to 400% to stretch the band. `Squash` switches the slider to
   25% - 100% to shrink it instead. The preview updates live.
5. **Save** writes a full resolution copy to `Pictures/PhotoStretcher/photostretcher_YYYYMMDD_HHMMSS.jpg`
   and a **Share** button appears.

Extra features, all on the editor screen:

| Feature | How |
| --- | --- |
| Undo / Redo | Bottom bar. Undo and redo walk back through committed stretches. |
| Multiple stretches | **Apply** locks a stretch in, then you pick a new band on the result. |
| Horizontal mode | The `Vertical` / `Horizontal` chip switches to left/right lines. |
| Smooth edges | Cross fades the two seams over a few pixels instead of hard cutting. |
| Save as PNG | The `JPG` / `PNG` chip switches the file type. |
| Start over | Throws every committed stretch away and returns to the original. |

See [NOTES.md](NOTES.md) for how the stretch maths works.

---

## Building the APK

The repository builds itself on GitHub Actions: push to `main` and the workflow runs the unit
tests, then compiles both a debug and a release APK and attaches them to the run as the
artifact **`photo-stretcher-apks`**.

```bash
./gradlew test                # checks the stretch maths, no device needed
```

You can also build it yourself, everything is checked in:

```bash
git clone <this-repo>
cd PhotoStretcher
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

Requirements: **JDK 17** and an **Android SDK with API 35** (`ANDROID_HOME` set, or
`local.properties` containing `sdk.dir=/path/to/Android/sdk`). Gradle itself comes from the
checked in wrapper, so nothing else has to be installed.

```properties
# local.properties (not checked in)
sdk.dir=/home/you/Android/Sdk
```

### Installing the release APK

The release build is signed with the standard Android debug key so that CI can produce an
installable APK without any secrets. That is fine for testing and for a personal build, but
replace it before publishing anywhere:

```kotlin
// app/build.gradle.kts
signingConfigs {
    create("release") {
        storeFile = file("my-release-key.jks")
        storePassword = "..."
        keyAlias = "my-key"
        keyPassword = "..."
    }
}
```

### Version map

| | |
| --- | --- |
| Gradle | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 (Compose 1.7.6, Material3 1.3.1) |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |

---

## Permissions

| Permission | When it is asked for |
| --- | --- |
| none | Picking a photo uses `ACTION_PICK_IMAGES` on Android 13+ and the system document picker below that. No permission needed. |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`) | Only on Android 8 and 9, the moment you press **Save**, because writing into the public Pictures folder needs it there. From Android 10 on, `MediaStore` writes the file instead. |

The app also registers a `FileProvider` so a saved picture can be shared on Android 8 and 9.

---

## How the code is laid out

```
app/src/main/java/com/photostretcher/app/
├── MainActivity.kt          two screens, one piece of state
├── model/StretchOp.kt       one stretch: axis, band as fractions, factor
├── engine/
│   ├── StretchMath.kt       the maths: positions, folded into straight cuts
│   ├── StretchRenderer.kt   draws those cuts into a bitmap (bilinear, 1:1 where possible)
│   ├── MemoryPlan.kt        keeps the export inside the device's memory
│   ├── ImageDecoder.kt      downsampled preview, EXIF rotation, safe decoding
│   └── ImageSaver.kt        MediaStore on 10+, public folder below, never overwrites
└── ui/
    ├── PhotoCanvas.kt       the live preview plus the two draggable lines
    ├── EditorControls.kt    line positions and the slider, in fractions
    ├── PhotoViewModel.kt    the photo, the committed stretches, undo/redo, export
    ├── EditorScreen.kt      the editor
    ├── HomeScreen.kt        the start screen
    └── theme/Theme.kt       dark colour scheme

app/src/test/java/com/photostretcher/app/engine/
└── StretchMathTest.kt       walks every layout the way drawBitmap does and checks that the
                             source is covered once, in order, and that only the band moved
```

The rule that keeps preview and export identical: **line positions are stored as a fraction of
the picture (0.0 - 1.0), never as screen pixels.** The preview multiplies the fractions by the
preview size, the export multiplies the very same fractions by the size of the full resolution
photo.

---

## Notes and limits

- Band minimum gap is 2.5% of the picture.
- The stretch slider is 100% - 400%, squash is 25% - 100%.
- If the stretched result would not fit in memory (a 12MP photo stretched by 4x needs about
  144MB as a bitmap), the export is scaled down to fit and the message says so.
- Very large photos are never decoded at full size for the preview; the editor works on a copy
  whose long side is at most 1400 pixels.

## Licence

MIT, see [LICENSE](LICENSE).
