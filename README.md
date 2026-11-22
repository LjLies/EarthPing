# Alertroid

Earthquake early warnings and public alerts for Android, **without Google Play Services**.

Alertroid listens to the push broadcasts Google sends for the Android Earthquake Alerts
System. It talks to Google's push server directly, so it works with microG or with no
Google software at all. It is 100% free software (GPL-3.0-or-later). Its only dependencies
are AndroidX AppCompat and Preference, Material Components and
[osmdroid](https://github.com/osmdroid/osmdroid) for the map, all Apache-2.0.

> [!CAUTION]
> Alertroid is unofficial. It relies on an undocumented Google service that can change or stop
> at any time, and it is not a certified warning system. Keep your phone's official emergency
> alerts enabled.

## What it does

- **Your location.** When an alert arrives for your area, Alertroid:
  - sounds a siren on the *alarm* stream at full volume. With your permission it also lifts
    Do Not Disturb for the alert, then puts back your volume and DND settings;
  - shows a full-screen warning, even over the lock screen and with the display off. The
    warning has an OpenStreetMap map with the epicentre, your position, Google's intensity
    contours (MMI 5+/4/3) and the **P- and S-wave fronts moving outwards**;
  - counts down the **seconds until the S-wave** (the strong shaking) on screen, in the
    notification, and by **text-to-speech** when a TTS engine is installed;
  - updates the warning in place when Google revises the magnitude or area seconds later.
- **Watched places.** You can pick up to three other places (search, coordinates or a
  long-press on the map). You get a normal notification about earthquakes there, with no
  countdown. These places are also the protocol's privacy *decoys*. Empty slots get random
  automatic decoys, whose alerts are only logged.
- **Other public alerts.** Google uses the same channel for floods, storms, fires and similar
  alerts ("crisis alerts"). You are notified about the ones for your area and your watched places.
- **Test mode.** Choose a delay, the seconds until shaking and a magnitude. Alertroid then
  stages a complete alert near you, so you can rehearse it, including with the screen locked.

## Languages

English and Italian. On Android 13 and later, choose the app's language in the system
settings (Settings → Apps → Alertroid → Language). On older versions, choose it in
Alertroid's own settings. The spoken warnings have their own language setting, so the
app can be in one language and speak in another.

## How the protocol works

This is a summary. The protocol was learned from the research in
[quake-alert-listener](https://github.com/Juanipis/quake-alert-listener). None of its code is
used here: everything is reimplemented in Kotlin.

1. **Anonymous identity.** Alertroid sends a protobuf `POST` to
   `https://android.clients.google.com/checkin`, which returns an `android_id` and a
   `security_token`. This identity is separate from microG's. It is refreshed every
   `checkin_interval`, about 2 days.
2. **MCS connection.** Alertroid opens a TLS connection to `mtalk.google.com:5228` (or port 443
   as a fallback). It sends the version byte `41`, then a `LoginRequest`. Field 29 of that
   request lists the **topics** to subscribe to:
   - `ea.<S2 token>` of the **level-8 S2 cell** (about 40 km wide) that contains you;
   - three decoy cells.
3. **Alerts.** Google publishes each alert to the topics of the cells it affects. It arrives as
   a `DataMessageStanza` with a `rawData` protobuf (an *AlertBatch*). Each alert in the batch
   carries:
   - an id and issue time;
   - a mode (live, silent or test);
   - a type (5 = earthquake, 1 = public alert, 2 = SOS);
   - an area: intensity contours as polygons or circles, a compressed `S2Polygon`, or S2 cells;
   - localized texts;
   - for earthquakes, magnitude, epicentre, depth and **origin time**.

   From these, Alertroid estimates the local intensity (Allen, Wald & Worden 2012) and the
   P/S-wave arrival times.
4. **Liveness.** Alertroid sends heartbeats from exact `AllowWhileIdle` alarms, so they keep
   working in Doze. A heartbeat with no answer within 20 s means the connection is dead.
   Received message ids are acknowledged, so Google doesn't send them again.

## Staying alive

- A **foreground service** (`specialUse` type, plus `location` when allowed) keeps the
  connection open.
- **Onboarding** asks for unrestricted battery use, which also lets the service restart
  itself. It also asks for unrestricted background data if Data Saver is on, exact alarms,
  "Display over other apps", full-screen intents, Do Not Disturb access, and location (including
  "all the time"). Required items block onboarding until you grant them. The main screen keeps
  warning you about anything still missing.
- **Restarts** happen at boot, after app updates, after the task is swiped away, and from a
  15-minute `JobScheduler` watchdog.
- **Reconnects** happen right away when the network changes. After failures, Alertroid backs off
  exponentially (2 s → 10 min, with jitter) and makes **at most 30 attempts per hour**. It
  registers at most **3 new identities a day**, so Google's servers are never hammered. After a
  long outage you get a notification.

## Location

Alertroid uses the platform **network** location provider. This is coarse location; on microG it
is provided by UnifiedNlp. Passive fixes from other apps are used as well.

**GPS is used only sparingly.** It is tried only when the network provider has been silent for an
hour, for at most 60 seconds, and it stops at the first fix. Attempts back off from every 2 hours
to every 12 hours.

You can also set your location by hand. Precision doesn't matter much: the alert cell is about
40 km wide.

## Privacy

- **Sent to Google:** the topics, meaning your ~40 km cell mixed with three decoys, plus an
  anonymous checkin. By default the checkin uses a generic device profile, not your phone's
  build fingerprint.
- **Map tiles:** these are downloaded from OpenStreetMap when a map is shown.
- **Nothing else** is collected, and there are no analytics.

## F-Droid notes

- The app has no Google Play Services, Firebase or proprietary libraries.
- `dependenciesInfo` is disabled, so the APK carries no Google-encrypted dependency metadata.
- Store metadata is in `fastlane/metadata/android`.
- Expected anti-feature: **NonFreeNet**, because the app relies on Google's push service.

## Building

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

Render the screens to PNG files, like the screenshot test does:

```bash
./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -Pscreenshots=/tmp/shots
```

The live test performs a real checkin and MCS login:

```bash
ALERTROID_LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveProtocolTest*'
```

The build requires JDK 17+ and Android SDK 35.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).
