# Audilege

Audilege is an Android reader and audiobook player for Audilege Server and [BookOrbit](https://bookorbit.app/). Stream books from your server, read along with synchronized text highlighting, and resume from the same passage on another device.

[Download the latest Android release](https://github.com/yourneeds-app/audilege/releases/latest)

## Getting started

- Open **Categories → Discover** to browse all titles, or select an available media category. Use **Sort** to change the order. Server and profile selection is at the bottom of the drawer, above Settings.
- The yearly statistics include a daily activity calendar. Short words without individual timestamps are highlighted alongside a timed word in the same marked sentence when the EPUB provides word spans.

- With Audilege Server, choose the existing account sign-in option and enter your own server address and Audilege Server credentials.
- While reading along, open **Read-along settings → Highlight text** to choose **Word** or **Sentence**. Word-level timing comes from the book's audio overlays. Audilege remembers your preference.

- Add your BookOrbit server under **Profile → Add profile** and sign in with your existing account. Audilege stores session tokens in encrypted storage and refreshes them when needed. Existing OPDS profiles and **Connect via OPDS** are also supported.
- Above **My books**, swipe through **Continue reading** and tap a book to resume at its saved position. This shelf includes unfinished local books and supported EPUBs in your BookOrbit account. Browse the full library as a cover grid or a list, or search by title or author. On wide screens, the library and book details appear side by side.
- Choose **Read**, **Listen**, or **Read along** on the book details screen. For compatible BookOrbit books, all three modes can start from the server. Use **Save offline** to keep a copy that can be opened or removed under **Downloads**.
- Tap the page to show the controls. Under **Aa**, adjust the font, size, line spacing, margins, paper color, and read-along highlighting. Use the table of contents, search, and bookmarks to move between passages.
- In **Listen**, use the chapter controls, time jumps, playback speed, and sleep timer. Audilege skips text-only sections during playback. The mini-player, connected headphones, and Android media controls can also control playback.
- Audilege remembers where you paused or stopped listening on this device. In **Settings → Playback**, choose whether to jump back 5, 10, 15, or 30 seconds when you resume. The default is **Off**.
- Open **Audiobook equalizer** from the player menu while listening or reading along, or from **Settings → Playback**. Try **Clear voice** for a muffled recording, **Warm voice** for a thin voice, or **Gentle treble** for sharp S sounds. Under **Fine-tune**, adjust bass, voice presence, and treble by up to 6 dB each. Changes apply as you listen and are saved on the device. **Off** plays the original sound. The player uses the available Android audio effects for local output and Bluetooth, and processes AirPlay audio before sending it to the receiver.
- While Audilege is in the foreground, its volume buttons show a short in-app card with the current output, volume bar and percentage. Phone/Bluetooth volume uses the Android media stream; an active AirPlay connection controls that receiver. Open **Audio output** for a slider for the current output. In-app changes do not request the Android volume panel. Background and lock-screen controls keep using the existing Android media session. The volume card follows the current player theme and respects longer accessibility display times.
- Open **Settings** from the menu to choose English or German, adjust the theme and accent color, enable cover-based colors, and manage update checks.
- **Profile & server** in the menu shows the selected profile name. Open it or the **Profile** tab to see clearly separated cards with server addresses, account type and the currently selected profile first. Matching profile names and usernames are shown once. Each card offers editing and switching; imported books remain available under **On this device**. Settings stays at the bottom of the menu.
- **Statistics** opens your current activity year. Choose **All activity**, **Reading**, **Audiobooks** or **Read aloud**, then **Week**, **Month**, **Year** or **All time**. Bar charts show daily, monthly or yearly totals; tap a bar for its time. Rankings include small book covers and authors. Streaks use your BookOrbit account's timezone and rules across all activity types: any positive activity counts, and the current streak may end yesterday.

### BookOrbit activity statistics

Starting with 0.4.11, statistics use confirmed BookOrbit history only. Previous app-only totals, caches and unsent legacy statistics are ignored; existing server history is retained. OPDS-only and unlinked local books do not contribute to this account view. Read, listen and text-to-speech sessions from accessible server books are imported separately. Unknown session types remain in All activity and are not guessed to be audiobooks. Read aloud shows recorded server TTS activity; this update does not add a new speech engine.

New listening and reading activity is queued before uploading. Reading time counts only while a ready page is visible and focused, with the same five-minute inactivity limit as the BookOrbit webreader. Audio time excludes pauses, buffering and seeks. Uploads run about once a minute, at pause/stop or leaving the reader, and on opening Statistics. The open statistics screen refreshes all accessible history every five minutes or via **Sync now**. Pending activity is shown as pending, not added to the confirmed totals. Install this update on both devices and open Statistics once on each. Reading-position sync remains separate.

The file-scoped session API receives the edition ID, a stable session ID, observed start/end times, active seconds, the truthful activity type, and source `android`. Retries use the same IDs, and the queue clears only after the server history confirms the uploaded sessions. BookOrbit requires ten seconds per session; shorter intervals and subsecond remainders wait until they can be combined. Rejected or incomplete responses preserve the last confirmed snapshot and pending activity. Statistics go only to your selected BookOrbit server, not GitHub or another statistics service. App-Attest-protected APIs are not bypassed.

Calendar recaps use actual recorded time, not book length or seek position. Sessions spanning midnight use BookOrbit's account timezone, daylight-saving boundaries and whole-second rounding. Changing the account timezone rebuilds all dated book rankings. The daily average includes elapsed calendar days without activity. Active books means books with time in the selected activity and period. Completed books is the separate all-time count supplied by BookOrbit. Current and longest streaks are account-wide and stay the same when switching the activity filter or displayed year. Multi-author labels are retained as supplied. Compatibility was checked against the BookOrbit 3.9.0 source and simulated API responses; a real-device comparison against an authenticated server remains to be performed.

## Updates

Starting with Audilege 0.4.2, choose **Settings → App updates → Download and install update**. Audilege downloads and checks the APK, then opens the Android installation confirmation. Android may ask for permission the first time you install an update through Audilege.

If you are using Audilege 0.4.1 or earlier, download the latest APK from GitHub Releases and install it over your existing copy. The APK uses the same package ID and signing certificate.

## BookOrbit and EPUB support

Audilege signs in through the BookOrbit account API. It uses the server's EPUB resource and media-overlay endpoints for streaming and the download endpoint when you request an offline copy. If a book has several editions, Audilege prefers an EPUB with media overlays. OPDS supports catalog browsing and offline downloads. HTTPS is the default. You can enable local HTTP for individual profiles.

Audilege supports DRM-free EPUBs with XHTML text and optional EPUB 3 media overlays. Highlighting follows the timing marks in each edition. Reading and listening positions sync per BookOrbit account and EPUB edition. Audilege stores pending changes locally and lets you choose which position to keep when devices report conflicting changes. Android provides background playback through the media service. Fixed-layout EPUBs use the standard reader view.

## AirPlay 2

In **Listen**, tap the audio output icon and select a compatible AirPlay 2 receiver on the same Wi-Fi network. Audilege plays audio from BookOrbit or an offline copy and sends it to the receiver.

On Android 11 and later, available receivers also appear in the Audilege media card's output picker. The card can switch devices and adjust volume during a listening session. Availability on the lock screen and in Quick Settings depends on the Android device.

Audilege estimates the current listening position from the stream and its buffering delay. When you switch from AirPlay to **Read** or **Read along**, Audilege pauses playback and opens the corresponding passage. A lost connection also pauses playback. A BookOrbit account profile is required to sync between devices. The audio output picker includes the sender's license notices.

## Build and test

Open the project in Android Studio with JDK 17 or 21, Android SDK 35, and Gradle 8.14.

```sh
gradle testDebugUnitTest assembleDebug lintDebug
```

- [BookOrbit OPDS documentation](https://bookorbit.app/opds/)
- [EPUB 3.3 specification](https://www.w3.org/TR/epub-33/)
- [Android media routing documentation](https://developer.android.com/media/routing)
- [Build and release instructions](RELEASING.md)

### Earlier releases

- **0.2.1 (build 4)** keeps audio continuous between successive timing marks in the same track. Highlighting follows playback time and clears between word marks.
- **0.2.0 (build 3)** passed the APK build, eight unit tests, and Android lint. It uses the same package ID and signing certificate as FolioCue 0.1.1.
- **0.1.0** passed APK and signature verification. Android lint reported 19 warnings and zero errors. Four parser tests passed. The parser loaded 58,199 timing marks from *Krummer Hund* and 306,375 from the collected *Hunger Games* edition.
