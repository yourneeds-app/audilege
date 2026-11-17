# Audilege

Eigenständiger Android-Client unter dem Label Your Needs, zuvor FolioCue. Die Android-Paket-ID bleibt für Updates erhalten. Die App verwendet Kotlin und Jetpack Compose. Der Reader wurde anhand der EPUB-Spezifikation entwickelt.

## Bedienung

- Unter **Menü → Server & Profile → Profil hinzufügen** die BookOrbit-Adresse und den Benutzernamen und das Passwort des bestehenden BookOrbit-Kontos eingeben. Der Profilname ist optional. Die App speichert bei der BookOrbit-Anmeldung ausschließlich die Sitzungstokens verschlüsselt. Die Sitzung wird bei Bedarf erneuert. Vorhandene OPDS-Profile und die Option **Mit OPDS verbinden** stehen weiterhin zur Verfügung.
- Das Hamburger-Menü öffnet Bibliothek, Downloads, EPUB-Import und Profile. Im geöffneten Buch führt es zum Inhaltsverzeichnis, zur Darstellung, in den Lesemodus ohne Leisten und zurück zur Bibliothek. **Weiterlesen** öffnet den letzten lokalen Titel des gewählten Profils an der gespeicherten Stelle.
- Im Bücherregal einen Katalog öffnen und ein EPUB herunterladen oder über den Dateiauswahldialog ein fertiges Mitlesen-EPUB hinzufügen. Über **Bücherregal** oder Android-Zurück kann man direkt zum nächsten Buch wechseln. Bücher und Fortschritt sind dem jeweiligen Profil zugeordnet.
- Im Reader stehen **Mitlesen**, **Nur hören** und **Nur lesen** für EPUBs mit Tonspur bereit. Die Modusleiste behält die aktuelle Passage. Ein Tipp auf den Text blendet die Bedienelemente ein und aus. **Inhalt** öffnet das Inhaltsverzeichnis, **Ansicht** die Einstellungen für Schrift, Schriftgröße, Papierfarbe und Tempo.
- Die Oberfläche unterstützt Deutsch und Englisch. Die Sprache ist unter **Server & Profile** umschaltbar.

## Server und EPUB

Die BookOrbit-Anmeldung verwendet `/api/v1/auth/login`, `/api/v1/books/query` und den berechtigungsgeschützten Datei-Download. Beim Öffnen bevorzugt Audilege ein EPUB mit eingebetteten Media Overlays. OPDS bleibt unter `/api/v1/opds` als alternativer Zugang nutzbar und liefert die Primärdatei. Ein fertiges `.readaloud.epub` lässt sich außerdem lokal importieren. HTTPS ist der Standard. Lokales HTTP wird pro Profil freigegeben.

Unterstützt sind DRM-freie EPUBs mit XHTML-Text und EPUB-3-Media-Overlays mit eingebettetem Audio. Die Tonspur wird lokal im Vordergrund abgespielt. Kapitel, Scrollposition und letzte Tonposition werden lokal gespeichert. Der Abgleich erzeugt hervorgehobene Wörter ausschließlich dort, wo das Buch Wortzeitmarken enthält. Festlayout-EPUBs, externe Links, eingebettete Skripte, serverübergreifender Lesestandsabgleich und Hintergrundwiedergabe sind außerhalb des aktuellen Builds.

## AirPlay 2

Die AirPlay-2-Integration ist vertagt. Sonora dokumentiert einen nativen Senderweg mit `airplay2-sender-cpp`. Für FolioCue sind insbesondere die Lizenzhinweise der Abhängigkeiten und die Synchronisierung der Wortmarkierung mit der hörbaren Position am Lautsprecher zu prüfen. Der aktuelle Build spielt lokal ab.

## Bauen und prüfen

Projektordner in Android Studio öffnen. JDK 17 oder 21, Android SDK 35 und Gradle 8.14 verwenden.

```text
gradle testDebugUnitTest assembleDebug lintDebug
```

- OPDS: [BookOrbit-Dokumentation](https://bookorbit.app/opds/)
- Medienüberlagerungen: [EPUB 3.3](https://www.w3.org/TR/epub-33/)
- Audioauswahl: [Android Media Routing](https://developer.android.com/media/routing)


Stand 17. November 2026: Debug-APK 0.1.0 gebaut und Signatur geprüft. Android-Lint meldet 0 Fehler und 19 Warnungen. Vier Parser-Tests liefen direkt mit JUnit, weil die Sandbox die Loopback-Verbindung des Gradle-Testprozesses sperrt. Der Parser liest 58.199 Zeitmarken aus Krummer Hund und 306.375 aus der Panem-Gesamtausgabe. Wiedergabe und Oberfläche auf einem Android-Gerät sowie Anmeldung und Download an einem realen OPDS-Server stehen aus.

Audilege 0.2.0 (Build 3): APK, acht Unit-Tests und Android-Lint geprüft. Die APK nutzt dieselbe Paket-ID und Signatur wie FolioCue 0.1.1. Der vollständige Test mit einem echten BookOrbit-Konto auf Android steht aus.

Audilege 0.2.1 (Build 4): Zwischen vorwärts laufenden Zeitmarken derselben Tonspur läuft die Wiedergabe ohne Sprung weiter. Die Hervorhebung richtet sich nach der Android-Wiedergabezeit und bleibt in einer Lücke zwischen Wortmarken leer.
