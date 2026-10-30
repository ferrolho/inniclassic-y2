# InniClassic

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%204.4-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/Device-Innioasis%20Y2-blue?style=flat-square" alt="Device">
  <img src="https://img.shields.io/badge/Status-Beta-Orange?style=flat-square" alt="Status">
</p>

InniClassic is an iPod Classic-style launcher and music player for the Innioasis Y2. It replaces the stock app with the iPod classic menus, Cover Flow and Now Playing screen, driven by the Y2 click wheel, and adds Wi-Fi and Bluetooth features the iPod never had: casting to speakers, Jellyfin sync, podcasts, FM radio and Last.fm scrobbling.

---

## Screenshots

| Main Menu | Now Playing | Music |
|---|---|---|
| ![Main Menu](docs/screenshots/main-menu.png) | ![Now Playing](docs/screenshots/now-playing.png) | ![Music Menu](docs/screenshots/music-menu.png) |

| Artists | Album View | Cover Flow |
|---|---|---|
| ![Artists](docs/screenshots/artists.png) | ![Album View](docs/screenshots/album-view.png) | ![Cover Flow](docs/screenshots/cover-flow.png) |

---

## The Innioasis Y2

- 2.4" 480×360 screen, click wheel with centre button, Menu, Play/Pause, Previous and Next buttons.
- MediaTek MT6582 with Android 4.4.2 (API 19), 1 GB RAM, internal storage and a microSD slot.
- Cirrus Logic CS43131 DAC for the headphone jack and the speaker.
- Wi-Fi, Bluetooth 4.2 (A2DP, AVRCP) and an FM tuner.

InniClassic runs as the Android home app, so the Y2 starts straight into the iPod menus. The same APK also runs on the Innioasis Y1.

## Features

### The iPod Classic look
- Classic Light and Classic Dark themes with the iPod classic proportions: 30 px status bar, 38 px rows with a drawn chevron, Nimbus Sans text. *Auto* switches between them at local sunrise and sunset; theme changes apply at once, without a restart.
- Split-screen main menu and Music menu with a slowly panning and zooming album cover, as on the iPod.
- Menus slide in from the right and back out to the left; Cover Flow opens between the two panes.
- iPod status bar: screen title, clock, Wi-Fi, Bluetooth and headphone icons, and a battery with optional percentage digits.
- Low-battery banner at 10 % and an iPod *Please charge* screen at 2 %.
- Questions and progress (delete a playlist, pairing codes, the USB prompt, FM scan) show as list screens, not Android dialogs.

### Now Playing
- Angled album cover with reflection, glass progress bar, and iPod times (`2:08`, `-0:34`, `1:02:08`); *Total Time* shows the total in place of the remaining time.
- One centre press shows full-width lyrics, synced to the song from `.lrc` files or embedded tags (`USLT`, FLAC and ALAC lyrics). An optional `<song>.romanised.lrc` adds pinyin or romaji under each line, or in place of it.
- *Spectrum* shows a spectrum in the cover colour in place of the art.
- Holding the centre button opens the song menu: Add to Playlist, On-The-Go, Favorites, Browse Album, Browse Artist.
- Hold Next or Previous to seek (4× up to 120×); hold Play/Pause to stop.
- Star ratings and favorites.

### Music library
- Browse by Artists, Album Artists, Albums, Compilations, Songs, Genres, Composers, Playlists, Folders and Favorites; hide or reorder the items in *Settings → Menus*.
- Cover Flow in the iPod order (artist, then album) and layout, smooth on a fast spin.
- A fast wheel turn jumps letter by letter in the alphabetical lists and in Cover Flow, with a large letter box.
- *Shuffle Songs*, the smart playlists *Recently Played* and *Top 25 Most Played*, and On-The-Go playlists.
- MP3, FLAC, WAV, OGG Vorbis, Opus, ALAC/M4A and AAC; gapless playback on short queues.
- Cover art from tags or `cover.jpg`/`folder.jpg`; use square images of 600×600 or more.
- Music on the internal storage and on the microSD card, with a card watchdog that repairs and remounts a damaged card.

### Sound and controls
- 30 equal volume steps of 2 dB, saved for each output (speaker, headphones, Bluetooth).
- Equalizer, Bass Boost and Virtualizer run only when they change the sound.
- One action for each button push, on release; one vibration and one click for each wheel step, with their own levels.
- Settings in iPod-style groups (Playback, Display, Now Playing, Status Bar...) with On/Off switches, and Export/Import of all settings as one JSON file.
- A long press of the Power button opens a *Power* screen (Restart, Power Off) that the wheel can use.

### Wi-Fi and Bluetooth
- *Play On*: plays the music on a Sonos, DLNA or Google Cast speaker on the same Wi-Fi; the wheel stays in control.
- Bluetooth headphones and cars: pairing with the wheel only, track title and artist over AVRCP, and car or headphone buttons work with the screen off. Music pauses when the device disconnects and plays again when it comes back.
- Jellyfin music sync with QuickConnect, podcasts (subscribe, stream, download), music transfer through a web server, and time sync from the internet.
- Last.fm scrobbling: plays go to `.scrobbler.log` (Audioscrobbler 1.1) and, with a Last.fm account, to Last.fm.

### Beyond music
- FM radio on the internal tuner.
- Audiobooks with automatic bookmarks.
- Videos (libVLC) and the iPod Music Quiz.

## Known limitations

- Gapless playback works on queues of 15 tracks or fewer, with shuffle off and without FLAC tracks.
- For videos, encode around 640×480, 30 fps and 2 Mbps or less (see [`tools/handbrake_y1_preset.json`](tools/handbrake_y1_preset.json)).
- Wired remote buttons (EarPods) do not work: the Y2 kernel does not detect them.

## Installation

### ROM (recommended)

Prebuilt firmware with InniClassic as the home app is on [Releases](https://github.com/JonaszPotoniec/inniclassic-y2/releases) (`rom_y2.zip`).

1. Download `rom_y2.zip`.
2. Open the [Innioasis Updater](https://www.innioasis.com/pages/download), choose the zip as the local firmware package, and follow the steps.
3. When the Y2 restarts, InniClassic starts.

> [!WARNING]
> A full flash erases the internal storage. Keep the Y2 connected until the update finishes.

### APK update

To update only the app (the ROM has ADB on):

```bash
adb uninstall com.themoon.y1                    # or: adb shell pm uninstall --user 0 com.themoon.y1
adb install InniClassic-<version>.apk
```

Uninstall first if the new APK has a different signing key. Your music, themes in `/storage/sdcard0/Y1_Themes` and `.scrobbler.log` stay.

## Pre-building the library cache

For a large library, make the scan cache (`.y1_library_cache.json`) on a computer, with the microSD card in a card reader:

```bash
pip install mutagen
python3 tools/build_library_cache.py /path/to/sdcard
```

The Y2 then only checks that the files exist.

## Building from source

```bash
./gradlew assembleDebug testDebugUnitTest
```

- Needs JDK 17 and the Android SDK.
- Last.fm keys are not in the repository. Get them at [last.fm/api/account/create](https://www.last.fm/api/account/create) and add `lastfm.api.key` and `lastfm.api.secret` to `local.properties`; without them, everything works except the Last.fm upload.
- `scripts/build-rom.sh y2 --launcher-apk <apk> <out.zip>` builds the Y2 ROM. It mounts ext4 images, so run it on Linux (or in a privileged Docker container).

## Credits and license

- Original InniClassic project by Fabian Zettl: [FabianZettl/inniclassic](https://github.com/FabianZettl/inniclassic).
- Derived from [ismileblue/y1_launcher](https://github.com/ismileblue/y1_launcher) (JJ Launcher / MO-ON Launcher) by ismileblue.
- The iPod Classic interface uses [Nimbus Sans](https://en.wikipedia.org/wiki/Nimbus_Sans) (URW++), a metric-compatible alternative to Helvetica.
- Distributed under upstream's license terms; see [LICENSE](LICENSE) for personal and educational use guidelines.

Version history is on [GitHub Releases](https://github.com/JonaszPotoniec/inniclassic-y2/releases).
