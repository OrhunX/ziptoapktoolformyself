# The Ultimate Music Widget — v1.0

SEN0413 · Project 17 (App Widgets / Extensions)
Orhun Utku Topal · Jaweria Qadir · Mustafa Alhussein

One home-screen widget — and a full player app behind it — for every music app on the
phone, with a visualizer driven by the real audio. No accounts or API keys are needed.

## Open and run

1. Android Studio (2025.1 Narwhal or newer) → **File › Open** → select this folder.
   The first Gradle sync downloads AGP 8.11.1, Kotlin 2.1.21 and Media3 1.6.1, and may ask
   to install **Android SDK Platform 36** — accept. If Studio offers an AGP upgrade, skip it.
2. Connect an Android phone (Android 8.0+, USB debugging on) or start an emulator.
   A real phone is recommended: Spotify / YouTube Music are already installed and signed in.
3. Run the **app** configuration.

Unit tests (18): `./gradlew test`.

## What is in the app

| Area | What it does |
|---|---|
| **Player** | Artwork, title, seek bar, transport. Four real-audio visualizer styles: Ring, Bars, Mirror, Wave. Colors of the whole screen come from the album cover. |
| **Library** | Three bundled demo tracks plus every audio file on the phone (MediaStore). |
| **Hub playlists** | Playlists that mix local files with songs from Spotify, YouTube Music or any player. Each entry is played by the app it belongs to; the hub moves on when a song ends. Stored in SQLite. |
| **Favorites** | The heart (in the app and on the widget) saves the current song from *any* app. |
| **Setup** | Each permission next to the feature it unlocks. |
| **Widget** | 2×1 / 4×2 / 4×4, visualizer in every size (Bars, Mirror, Dots), source chip, video chip, favorite, *Up next* list, editor with live preview. |

## Demo script (about 4 minutes)

1. **Setup tab** — allow the visualizer (microphone), "Control other apps" (notification
   access), music on this phone, notifications; tap **Add** to place the widget.
2. **Library** — tap *Night Train*. On the **Player** tab the ring follows the music: bass
   at the bottom, treble at the top; the backdrop takes the cover's colors. Try the styles.
3. **Home screen** — the widget shows the same song; bars move with the music; play, next
   and the heart work. Resize it to 2×1 and 4×4. Long-press › reconfigure to restyle it.
4. **Hub** — play a song in Spotify, come back: the source chip turns green, the widget and
   the app now control Spotify. Tap the heart: the Spotify song lands in *Favorites*.
5. **Hub playlist** — Playlists › New playlist › add a demo track from the Library (＋) and
   the current Spotify song (*Add current song*) › **Play all**. The hub plays each entry
   in its own app and advances by itself.
6. **Video chip** — while Spotify plays, **Video** opens the song in Spotify; controls stay
   on the widget.
7. **Battery rules** — Battery Saver drops the widget visualizer from 18 to 10 fps; pause or
   screen-off stops it completely.

## Build the APK with GitHub Actions

1. Create an empty GitHub repository and push the **contents** of this folder to it, so that
   `settings.gradle.kts` and `.github/` sit at the repository root.
2. The workflow `.github/workflows/build-apk.yml` runs on every push to `main`/`master`
   (or by hand: **Actions › Build APK › Run workflow**). It runs the 18 unit tests, then builds
   the debug APK.
3. When the run is green, open it and download **UltimateMusicWidget-debug-apk** under
   *Artifacts*. It is a zip that holds `app-debug.apk`.

## Architecture

```
app/src/main/java/com/sen0413/ultimatewidget/
├─ hub/          HubController (single HubState), SourceSelector, ExternalTrackWatch,
│                external session watcher, audio-session registry
├─ data/         MusicLibrary (MediaStore), PlaylistStore (SQLite), TrackMatcher, ArtLoader
├─ playback/     PlaybackService (Media3), MediaListenerService (notification access)
├─ visualizer/   VisualizerEngine, BandMapper, VisualizerService (microphone-type FGS)
├─ widget/       HubWidgetProvider, WidgetRenderer, QueueWidgetService, prefs, theme
└─ ui/           MainActivity + four screens, custom views (ui/views), design kit (ui/kit)
```

* **One-way data flow:** sources → `HubController` → immutable `HubState` → every surface
  (widget, screens, editor preview). Surfaces never decide anything.
* **Smallest possible widget updates:** full update on first render or resize, partial
  updates for state changes, visualizer frames that contain only bar heights.
  `updatePeriodMillis` is 0.
* **Real audio, never faked:** `Visualizer` FFT → 16 log-spaced bands → bar heights. Sources
  in order: our player's session → a session the app announced → the output mix. No data
  means flat bars and a hint.
* **UI:** framework views and custom `Canvas` drawing, no XML for app screens; the widget
  itself must be `RemoteViews`, so its layouts are XML (`res/layout`, `res/layout-v31`).
* **Dependencies:** AndroidX Core, Media3 (ExoPlayer + Session), Palette, Coroutines.

## Honest limits

* **Verification:** this project was written without access to a device. All Kotlin was
  compiled against the Android 16 framework with hand-written stubs of the Media3/AndroidX
  signatures it uses, and the 18 unit tests pass; the Gradle build, resources and on-device
  behavior are first exercised on your machine. If something fails, the error text is all
  that is needed to fix it.
* **Other apps' audio:** Spotify blocks audio capture. While Spotify plays, the visualizer
  uses the device's output mix, which some phones (and the emulator) do not provide. Then the
  bars stay flat and the widget says so — controls still work.
* **Hub playlists with other apps:** the hub asks the app to play "title artist" through its
  MediaSession (`playFromSearch`, the request Assistant and Android Auto use). The app must
  be running, and what it plays is the app's own search result (Spotify Free may shuffle).
* **Background visualizer:** Android only lets the analysis start from a visible moment
  (the app on screen or a widget tap). After a long pause, open the app once or tap the
  widget's hint.
* Not included (need developer accounts): Spotify Web API / App Remote, YouTube Data API,
  exporting playlists to those services.

Fonts: Space Grotesk and DM Sans, SIL Open Font License (see `FONT_LICENSE_*.txt`).
Demo music and covers were generated for this project.
