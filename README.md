# Mein Terminplan für Android

Native Kotlin-App für Android 8.0 und höher mit `targetSdk 36` (Android 16). Öffne diesen Ordner in Android Studio. Benötigt werden Android SDK Platform 37.0, Build Tools 37.0.0 und JDK 17 oder neuer. Der Debug-Build kann mit `./gradlew assembleDebug` erstellt werden.

## Funktionen

- Foto in voller Auflösung aufnehmen oder Bild aus der Mediathek wählen
- lokale Texterkennung mit der gebündelten ML-Kit-Bibliothek; keine Übertragung an einen Server
- automatische Perspektivkorrektur erkannter Blattkanten mit OpenCV; bei unsicherer Erkennung bleibt das Originalbild erhalten
- Datum, Uhrzeit, Dauer, Terminbezeichnung und Informationszeilen nach den Regeln der iOS-App auswerten
- Termine lokal speichern und abgelaufene Einträge entfernen
- beim erneuten Einlesen nur die erkannten Tage ersetzen; gespeicherte Termine anderer Tage bleiben erhalten
- lokale Erinnerung zehn Minuten vor Beginn, auch nach Neustart erneut geplant
- Startbildschirm-Widget für den aktuellen oder nächsten Termin
- laufende Android-Benachrichtigung für den aktuellen oder nächsten Termin als Entsprechung der iOS-Live-Activity
- Launcher-Icons in allen Android-Auflösungen und als adaptives Icon aus dem iOS-App-Icon übernommen

Bitte die Ergebnisse nach jedem Scan mit dem Papierplan vergleichen. Android-Launcher entscheiden selbst, ob sie Widgets zusätzlich auf dem Sperrbildschirm anbieten.

## Technischer Stand

`assembleDebug` und `testDebugUnitTest` wurden mit `targetSdk 36` erfolgreich ausgeführt. Die App wurde zuvor im Pixel-7-Emulator mit Android 15 gestartet. Ein synthetischer Plan mit zwei Terminen und einer Informationszeile wurde über die Fotoauswahl korrekt eingelesen; die 10-Minuten-Alarme und die laufende Benachrichtigung wurden im Emulator geprüft. Ein Android-16-Laufzeittest steht noch aus. Die Erkennung sollte zusätzlich mit echten Formularfotos auf einem Gerät geprüft werden. Die Debug-APK liegt unter `app/build/outputs/apk/debug/app-debug.apk`.

## OCR für fotografierte A4-Pläne

Die OCR erhält bis zu 3508 Pixel an der langen Bildkante, ohne kleinere Bilder künstlich zu vergrößern. Blattkanten werden auf einer Vorschau mit maximal 1200 Pixeln erkannt; die Perspektivkorrektur nutzt das höher aufgelöste Bild. Textfragmente werden anhand ihrer Texthöhe und Position zu Zeilen verbunden, damit dicht gesetzte Terminzeilen getrennt bleiben. Auf Android 8 wird das Zwischenbild beim Dekodieren auf höchstens 16 Megapixel begrenzt.

Die Layout- und Skalierungsregeln sind mit Unit-Tests geprüft. Die tatsächliche Erkennungsqualität muss mit echten A4-Fotos (kleine Schrift, Schatten und schräge Aufnahme) auf Geräten verglichen werden.
