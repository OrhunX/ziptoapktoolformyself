# The Ultimate Music Widget — v1.2

SEN0413 · Project 17 (App Widgets / Extensions)
Orhun Utku Topal · Jaweria Qadir · Mustafa Alhussein

One home-screen widget — and a full player app behind it — for every music app on the
phone, with a visualizer driven by the real audio. No accounts or API keys are needed.

## What's new in 2.2

- **No more songs starting on their own.** A Spotify / YouTube Music song now counts as finished only when it really reached its end (its last known position, extrapolated while playing, is within 10 s of its length) or the app stopped on its last seconds. A title change while paused (apps refresh their session on their own) is ignored, so the next song can no longer start by itself on a phone lying on the table.
- Another song picked inside Spotify / YouTube Music before ours ended is the user's choice: the hub playlist ends and the hub no longer pauses it to play the playlist's next song.
- A hub playlist paused for 10 minutes stops acting on its own; Play continues the same song where it was (or asks the app for it again), and the widget keeps showing it.

## What's new in 2.1

- **Covers and versions stay apart.** Finding a YouTube Music song's video now requires the artist (or the version named in the title, e.g. "Kasane Teto cover") to match, not only the title, and also searches videos, where covers and fan uploads usually are. Before, a cover could play the original song.
- Video ids saved by earlier versions are checked once against YouTube (title and channel); ids of another version are dropped and looked up again. Exact ids, such as those of imported playlists, are kept.
- Playlist covers are no longer drawn faded, and YouTube songs are no longer greyed out when they can play inside the app.

## What's new in 2.0

- **The widget remembers the last song.** Its title, artist, cover, position and the hub playlist it came from are kept on disk, so after the app is closed (or the phone restarts) the widget still shows it instead of going blank.
- **Play continues where you left off.** Files on the phone resume at the same second; Spotify / YouTube Music songs start again from the beginning. A song from a hub playlist continues inside that playlist: next and previous move through the playlist.
- **Import playlists from Spotify and YouTube Music** (Playlists → "Import from apps"): choose from your own playlists, or paste any playlist link. Song covers come along.
  - Spotify uses the official Web API (playlist-read scopes). Under Spotify's February 2026 development-mode rules only playlists you own or collaborate on return their songs; other public playlists are read from Spotify's public embed page (unofficial).
  - YouTube Music uses the unofficial InnerTube API. Public and unlisted playlists need no account; private playlists and Liked music need signing in on Google's page (Settings → YouTube Music account). Requests are then signed with the SAPISIDHASH header, as the website does.

## What's new in 1.9

- **YouTube Music inside the app.** Playlist songs from YouTube Music now play right inside the app with YouTube's official embedded player (IFrame API in an invisible WebView, kept alive by a media foreground service), also with the screen off. No switching to the YouTube Music app, no screen changes; next, previous, seek, repeat and shuffle work exactly like local songs.
- Songs whose owner disabled embedding (or that fail to start within 15 s) automatically play through the YouTube Music app as before, and are remembered so they go there directly next time.
- Covers for these songs come from YouTube's thumbnail, cropped to the square album cover, and are stored with the other covers.
- Settings → "YouTube Music inside the app" turns this on or off.
- Note: background playback of embedded YouTube is outside YouTube's terms of service; this is a course-project demo only.

## What's new in 1.8

- **YouTube Music mostly in the background.** The first YouTube Music song of a playlist opens YouTube Music once, with all of the playlist's YouTube Music songs loaded as one temporary playlist (youtube.com `watch_videos` → `list=TL…`). Every later YouTube Music song is a background `skipToQueueItem` inside YouTube Music's own queue; when YouTube Music moves on to exactly the next hub song by itself, the hub keeps it instead of re-asking.
- Missing video ids are found with YouTube Music's unofficial InnerTube search (no login, as ytmusicapi does), prefetched when a playlist starts and learned from YouTube Music while songs play. This can break whenever YouTube changes it; the app then falls back to watch links and YouTube Music's search screen.
- When YouTube Music has to come to the screen, the app returns as soon as the requested song is loaded.

## What's new in 1.7

- **YouTube Music playlists move on.** YouTube Music ignores media-session "play this" requests from apps it does not trust and kept playing whatever it had loaded, so only the first YouTube Music song of a playlist played. The hub now starts YouTube Music songs with intents: a `music.youtube.com/watch?v=` link when the song's id is known, otherwise YouTube Music's play-from-search screen. It returns to the app (or the home screen when started from the widget) once the song plays; without the "display over other apps" permission a "tap to continue" notification is used.

## What's new in 1.6

- **Every chosen song starts at 0:00.** When an app resumes a paused song from its old position (YouTube Music does this when woken from another platform), the hub seeks to the start once the song really plays, verifies 1.5 s later and repeats up to three times.

## What's new in 1.5

- **Covers that stay.** Song covers are stored on the phone (one small JPEG per song in `files/art`, keyed by normalized title + artist): captured from the playing app once the cover is stable, read from local files, and fetched from Spotify's Web API for Spotify songs (exact id → `/v1/tracks`, else search). Playlist rows and the 2×2 playlist collages now show real covers even when nothing is playing; covers refresh in place without rebuilding lists.

## What's new in 1.4

- **No sample songs.** The bundled demo tracks and covers are gone; first-time users see a welcome card (allow music access, open the Library, set up sources).
- **Smoother song starts.** Widgets and the lock-screen card are drawn on a background render thread (newest state only), and the audio visualizer attaches on its own thread, so tapping a song no longer freezes the app for a moment.
- **Six widgets in the widget list.** Mini 1×1, Small 2×1, Square 2×2, Slim 4×1, Medium 4×2 and Large 4×4 are separate entries, each placed at its own size (all still resizable).

## What's new in 1.3

- **Spotify through its official SDK.** Spotify ignores "play this song" requests from apps it does not know and resumes whatever it had paused. Version 1.3 uses the Spotify Android SDK (App Remote, `app/libs`) to play the exact track (`spotify:track:...`) in the background. Approve once in Settings → Spotify (Spotify Premium is required by Spotify to pick songs; the app is in Spotify's development mode, so at most 5 approved accounts).
- Songs saved without a Spotify id are looked up once through the Web API (the token from approving the app) and learned automatically while they play.
- **A chosen song always starts from the beginning**, even when it is the song the app had paused (Spotify, YouTube Music and local files).
- When the SDK is unavailable (not approved, Free account, Spotify unreachable), the app falls back to the shared media session as before.

## What's new in 1.2

| Area | Change |
|---|---|
| **Hand-offs** | Switching between Spotify, YouTube Music and local songs never brings another app to the screen: requests go through the app's session (by its own song id when known, then by search). A closed app is woken through its media browser service; only while you are inside our app may it open briefly. "Next" stops the old song at once; a playlist ends by itself when you start something else. |
| **Playlists** | Rich cards with cover collages and colors, sorting (my order, name, recent, most played), drag to reorder playlists and songs, swipe to remove with undo, description, cover photo or emoji, color, pin, default shuffle/repeat, add to / mix with / copy to playlists, select several playlists and shuffle-play them without creating a new one. |
| **Shuffle and repeat** | Shuffle, repeat all, repeat one on the player, the lock player, the playlist header and the 4×4 widget. Playlists start over at the end (repeat all by default). |
| **Share codes** | "Share code" creates an encrypted `UMW1-…` code (optional PIN) with a QR code; "Enter code" imports it. Local songs travel as names only; the ones not found get Spotify / YouTube Music search buttons. |
| **Widget** | Six sizes: 1×1, 2×1, 2×2, 4×1, 4×2, 4×4. A "My colors" theme with your own background color, transparency and bar color. |
| **App colors** | Setup › Appearance: pick your own background color instead of the album colors. |

## What's new in 1.1

| Area | Change |
|---|---|
| **Cross-app playlists** | Every other player is paused before the next song starts, so only one app ever plays. The song is requested through the app's session first, then through its own play-from-search screen; if it loads paused, the hub presses play; a different song is never accepted in its place (after 15 s the hub says so and moves on). When Android does not let the hub open a closed app, a "tap to continue" notification appears. |
| **Lock screen** | Our card (cover, song, source, controls, heart) is shown for every source, including Spotify and YouTube Music. A full-screen player appears when the phone wakes while music plays: swipe the cover for next/previous, swipe up to unlock, ▾ shrinks it to the small card, tapping the card opens it again. |
| **Glass** | A frosted, translucent look for the app (blurred backdrop on Android 12+) and a Glass widget theme. |
| **Visualizer** | 10 styles in the app (Ring, Burst, Blob, Bars, Mirror, Wave, Peaks, LED, Spectrum, Particles), 6 in the widget (Bars, Mirror, Dots, LED, Center, Rainbow). Faster response, a beat pulse on every bass kick, and a sensitivity slider in Setup. |

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
| **Library** | Every audio file on the phone (MediaStore). The app ships without sample songs; a welcome card guides first-time users. |
| **Hub playlists** | Playlists that mix local files with songs from Spotify, YouTube Music or any player. Each entry is played by the app it belongs to; the hub moves on when a song ends. Stored in SQLite. |
| **Favorites** | The heart (in the app and on the widget) saves the current song from *any* app. |
| **Setup** | Each permission next to the feature it unlocks; appearance (Night / Glass), lock-screen player, visualizer sensitivity, a shortcut to hide Spotify / YouTube Music cards. |
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
5. **Hub playlist** — Playlists › New playlist › add a song from the Library (＋) and
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
* **Lock-screen player:** needs "Display over other apps" (Setup). Android cannot remove other
  apps' lock-screen cards; Setup links to their notification settings so you can turn them off.
  The Glass widget is translucent but not blurred (widgets cannot blur what is behind them).
* **Background visualizer:** Android only lets the analysis start from a visible moment
  (the app on screen or a widget tap). After a long pause, open the app once or tap the
  widget's hint.
* Not included (need developer accounts): Spotify Web API / App Remote, YouTube Data API,
  exporting playlists to those services.

Fonts: Space Grotesk and DM Sans, SIL Open Font License (see `FONT_LICENSE_*.txt`).
