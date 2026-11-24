# PTP clock component

Source: https://github.com/music-assistant/airplay-cli
Commit: 431c5c582eef9307c4e39c50a0ea65e970bc1128
Upstream files: src/ap2_ptp.c, src/ap2_ptp.h, src/ap2_ptp_shm.h
The upstream PTP source headers identify Music Assistant Contributors
(2024–2026) and carry the SPDX identifier Apache-2.0. The Sonora distribution
also supplied a GPL version 3 text in this directory as LICENSE. Both the
source headers and that file are preserved here.

Android changes: replaced crosstools/raop logging includes with ptp_platform.h; removed unused external loglevel. Sonora always supplies its own clock identity and starts one in-process clock per AirPlay session. Desktop shared-memory daemon support is disabled by shm_disabled.c; no daemon or extra service is installed. Headers retain upstream notices. No AirMusic code is used.

Sonora's original hardware test reported audible ALAC with PTP on a SYMFONISK
Bookshelf. That test used the Sonora sender. Audilege has its own application
bridge, playback clock and Android build.
