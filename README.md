# Audilege

Audilege is an Android reader and audiobook player for [BookOrbit](https://bookorbit.app/). Stream books from your server, read along with synchronized text highlighting, and resume from the same passage on another device.

[Download the latest Android release](https://github.com/yourneeds-app/audilege/releases/latest)

## Getting started

- Add your BookOrbit server under **Profile → Add profile** and sign in with your existing account. Audilege stores session tokens in encrypted storage and refreshes them when needed. Existing OPDS profiles and **Connect via OPDS** are also supported.
- Browse the library as a cover grid or a list, search by title or author, and resume the last book you opened. On tablets and other wide screens, the library and book details appear side by side.
- Choose **Read**, **Listen**, or **Read along** on the book details screen. For compatible BookOrbit books, all three modes can start from the server. Use **Save offline** to keep a copy that can be opened or removed under **Downloads**.
- Tap the page to show the controls. Under **Aa**, adjust the font, size, line spacing, margins, paper color, and read-along highlighting. Use the table of contents, search, and bookmarks to move between passages.
- In **Listen**, use the chapter controls, time jumps, playback speed, and sleep timer. Audilege skips text-only sections during playback. The mini-player, connected headphones, and Android media controls can also control playback.
- While Audilege is in the foreground, its volume buttons show a short in-app card with the current output, volume bar and percentage. Phone/Bluetooth volume uses the Android media stream; an active AirPlay connection controls that receiver. Open **Audio output** for a slider for the current output. In-app changes do not request the Android volume panel. Background and lock-screen controls keep using the existing Android media session. The volume card follows the current player theme and respects longer accessibility display times.
- Open **Settings** from the menu to choose English or German, adjust the theme and accent color, enable cover-based colors, and manage update checks.
- The menu shows the selected profile, username and server. **Listening statistics** opens your current listening year. Choose **Week**, **Month**, **Year** or **All time**, and use the arrows for earlier periods. Bar charts show daily, monthly or yearly totals; tap a bar for its listening time. Each period includes listening time, active days, daily average, best streak, busiest period, and top books with small covers plus top authors. The top five books are shown first, with an option to expand the full ranking. With a compatible BookOrbit account, new listening sessions sync through your server and can be refreshed on another device. Earlier device-only totals and unlinked local books stay under **Only local**; they are preserved and are not uploaded with invented timestamps. A streak day requires at least one minute of listening. Settings stays at the bottom of the menu.

### Listening statistics sync

BookOrbit accounts use the server's file-scoped listening-session API. Audilege queues sessions locally before uploading them, retries the same IDs after connection failures, and confirms the returned history before clearing the queue. Short intervals are collected because BookOrbit accepts sessions of at least ten seconds. Subsecond remainders and intervals below that minimum remain on the device until they can be combined. OPDS-only profiles and books without a known server edition remain local.

Uploads run about once a minute during playback and at pause or stop. Open **Listening statistics → BookOrbit** to refresh the account history, or tap **Sync now**. The open statistics screen refreshes every five minutes. Install a version with statistics sync on both devices. Reading-position sync remains separate.

For this purpose, the app sends the server edition ID, a session ID, observed start/end times, active listening seconds, and the listening/platform type to your selected BookOrbit server. It does not send these to GitHub or another statistics service. If the server rejects the API or cannot be reached, queued listening remains saved and the statistics screen shows the pending or error state.

Calendar recaps use actual recorded time, not book length or seek position. The daily average includes elapsed calendar days without listening. Books listened to means books with recorded listening time, not completed books. The best streak is limited to the selected period; the current streak is shown separately. Server history and new local activity retain a date for each book. Earlier local book totals without dates remain in All time and are not assigned to a month or year; incomplete period book counts show a lower bound or a dash. Refresh BookOrbit to rebuild dated rankings from server sessions. Multi-author labels are kept as supplied by the server, rather than split by guessing. These recaps do not send any additional data to another service.

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
