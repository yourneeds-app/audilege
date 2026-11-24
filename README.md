# Audilege

Android-Reader für BookOrbit. EPUBs lesen, Hörbücher streamen und mit synchroner Textmarkierung mitlesen. Der Lesestand wird mit dem Server abgeglichen.

## Bedienung

- Unter **Profil → Profil hinzufügen** die BookOrbit-Adresse und die Zugangsdaten des bestehenden Kontos eingeben. Der Profilname ist optional. Audilege speichert die Sitzungstokens verschlüsselt und erneuert sie bei Bedarf. Vorhandene OPDS-Profile und **Mit OPDS verbinden** sind ebenfalls verfügbar.
- Die Bibliothek zeigt das zuletzt geöffnete Buch mit einer Fortsetzen-Aktion. Coverregal und Liste lassen sich umschalten. Die Suche berücksichtigt Titel und Autoren. Auf breiten Fenstern stehen Regal und gewählte Buchdetails nebeneinander.
- Für BookOrbit-Bücher starten **Lesen**, **Hören** und **Mitlesen** direkt vom Server, soweit das EPUB die jeweilige Funktion unterstützt. **Offline speichern** legt eine lokale Kopie an. Diese lässt sich unter **Downloads** öffnen oder entfernen.
- Beim Lesen und Mitlesen blendet ein Tipp auf den Text die Bedienleisten ein. Unter **Aa** lassen sich Schrift, Schriftgröße, Zeilenabstand, Ränder, Papier und Mitlesemarkierung mit Vorschau einstellen. Buchsuche, Inhaltsverzeichnis und Lesezeichen helfen beim Springen zwischen Passagen.
- Beim Hören stehen Kapitel, Zeitsprünge, Tempo und Schlaftimer zur Verfügung. Die Wiedergabe lässt sich über den Miniplayer, verbundene Kopfhörer und die Android-Mediensteuerung bedienen.
- Das Hamburger-Menü öffnet die **Einstellungen**. Hier lassen sich unter anderem Sprache (Deutsch oder Englisch), App- und Akzentfarben, die Übernahme einer Coverfarbe sowie die automatische Updateprüfung wählen.

## Server und EPUB

Die BookOrbit-Anmeldung verwendet die Konto-API. Den Reader versorgen die EPUB-Ressourcen- und Media-Overlay-Routen. Die Download-Route wird beim ausdrücklichen Speichern einer Offline-Kopie verwendet. Audilege bevorzugt ein EPUB mit Media Overlays. OPDS bleibt für Katalog und explizite Offline-Downloads verfügbar. HTTPS ist der Standard. Lokales HTTP wird pro Profil freigegeben.

Unterstützt sind DRM-freie EPUBs mit XHTML-Text und optionalen EPUB-3-Media-Overlays. Die Markierung folgt den Zeitmarken der jeweiligen Ausgabe. BookOrbit-Lesestände werden pro Konto und EPUB-Datei geladen und gespeichert. Ausstehende Änderungen werden lokal gemerkt. Bei konkurrierenden Änderungen bietet der Reader beide Lesestände zur Auswahl. Der Player unterstützt Hintergrundwiedergabe über einen Android-Mediendienst. Für Festlayout-EPUBs gibt es keinen eigenen Darstellungsmodus.

## AirPlay 2

Im Modus **Hören** öffnet das Lautsprechersymbol die Audioausgabe. Einen kompatiblen AirPlay-2-Empfänger im selben WLAN auswählen. Das Handy streamt den Ton vom Server oder spielt eine gespeicherte Ausgabe ab. Die Verbindung zu BookOrbit verwaltet das Handy. Der Lautsprecher erhält den Ton.

Ab Android 11 bietet Audilege die gefundenen Lautsprecher auch in der Ausgabeauswahl seiner Android-Medienkachel an. Gerät und Lautstärke lassen sich dort während einer Hörsitzung wechseln. Die Darstellung der Kachel in den Schnelleinstellungen und auf dem Sperrbildschirm richtet sich nach der Android-Oberfläche und den Geräteeinstellungen.

Audilege schätzt die hörbare Stelle anhand der übertragenen Audiodaten und der Pufferzeit. Beim Wechsel von AirPlay zu **Lesen** oder **Mitlesen** pausiert die Wiedergabe an dieser Stelle. Eine Verbindungsunterbrechung pausiert ebenfalls. Audilege ordnet die Hörposition über die Zeitmarken des EPUB der passenden Textstelle zu. Der Abgleich mit anderen Geräten erfordert ein BookOrbit-Kontoprofil. Die Lizenztexte des Senders stehen in der Audioauswahl.

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
