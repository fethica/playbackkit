# playback-kit

Playback recovery for Media3 players on Android. It keeps a player going through the failures a streaming app actually meets: dropped connections, servers that stop answering, streams that stall in READY, and a device that goes offline and comes back.

Media3 already retries each failed load. playback-kit is the outer loop that runs after Media3 gives up, and it knows that a live item and a finite item recover differently.

Nothing is published yet. Build it from source.

## What it does

- **Live items**: re-prepares from the live edge on a backoff that starts at one second and doubles to a 30 second ceiling. A permanent failure (a 404, an unsupported format) stops the player instead of spinning, and is reported at once.
- **Finite items**: re-prepares at the retained position, up to three attempts. Slow buffering and the end of the item never spend that budget.
- **Watchdogs**: a buffering timeout for live items and a stall check for a position that stops moving while READY.
- **Suppression**: while a call or a navigation prompt suppresses playback, the buffering and stall watchdogs record no failure, and a rung that comes due waits until the suppression lifts. A player error or a network arrival during the suppression still enters recovery: it counts and schedules the next rung as usual, and that rung then waits.
- **Pauses**: a pause the listener asked for, a headset unplug, a remote pause or a permanent focus loss ends recovery. A delayed retry re-checks play intent before it runs.
- **Network**: offline failures wait for a network arrival instead of retrying blind. An arrival schedules the next rung of the ladder (for a live item deep in the ladder that can be up to 30 seconds away), it does not re-prepare on the spot. Arrivals can be signalled from any thread.
- **Stable playback**: the ladder never resets on a READY blip. Every READY starts a 30 second wall-clock window, cancelled by a failure, a seek or a new item; when it ends, the ladder resets if the player is playing at that moment. Time spent paused inside the window counts.

## Usage

Attach on the player's application looper, after the player is built. The player may already hold a prepared item: `attach` reads its current item and state and arms the matching watchdog, without treating the attachment as a new selection:

```kotlin
val recovery = PlaybackRecovery.attach(
    player = exoPlayer,
    host = object : RecoveryHost {
        override fun itemKind(mediaId: String?) =
            if (mediaId in finiteIds) ItemKind.FINITE else ItemKind.LIVE

        override fun hasNetwork() = connectivity.hasInternetCapability()

        override fun onEvent(event: RecoveryEvent) {
            if (event is RecoveryEvent.GaveUp) showPlaybackFailed(event.mediaId)
        }
    },
)
```

Signal network arrivals, from any thread:

```kotlin
override fun onAvailable(network: Network) = recovery.onNetworkArrival()
```

Detach before the player is released:

```kotlin
recovery.detach()
player.release()
```

### The host

`RecoveryHost` is the only thing recovery asks about your app.

- **`itemKind`**: live or finite, per media id.
- **`hasNetwork`**: the fail-open predicate. Answer from the INTERNET capability, not validation, and answer true when you cannot ask.
- **`automaticStartAllowed`** and **`onBeforeAutomaticStart`**: a veto for when your app stopped playback on purpose, a sleep timer for example.
- **`retrySourceFor`**: a different URI to retry a live item on, when your catalog has moved it.
- **`onEvent`**: everything recovery did, as a `RecoveryEvent`, delivered after the work that raised it is complete. It is for observing. To answer an event with player commands (play a fallback, seek), post them to a later turn of the looper, so the commands' own callbacks are not delivered in the middle of the ones still in flight.

### Events and reporting

`GaveUp` is emitted on every give-up, so the listener can be told each time. For analytics, feed events through `FailureReportDeduper` to report once per incident: the incident stays open across a stable stretch and closes on a real item transition.

### Same-id replaces

Wrap any in-place `replaceMediaItem` you make for the item that is playing (a metadata or artwork refresh) in `recovery.runHostReplace { }`. Choosing the item that is already playing with `setMediaItems` is a real restart and must not be wrapped.

### Listener ordering

`attach` registers its listener on the player. If your app must handle the end of an item before recovery sees ENDED, pass `registerListener = false` and forward `recovery.listener` callbacks from your own listener in the order you choose.

## Sample app

The `sample` module is a debug app that runs every recovery scenario on a phone, against generated audio served by a loopback server inside the app. It needs no network and no account.

```sh
./gradlew :sample:assembleDebug
```

The application id is `com.fethica.playbackkit.sample`, so installing it replaces nothing else.

### What it shows

- **A host service**: `PlaybackService` is the sample's own `MediaSessionService`, not a library service (the library ships none). It owns one ExoPlayer on the main looper, attaches `PlaybackRecovery` there with a `RecoveryHost`, forwards `ConnectivityManager` arrivals, and detaches before releasing the player.
- **Three items**: a 30 second finite tone and two live-like streams, the second one there for switching and reselecting. The host answers `itemKind` from the media id prefix (`live:` or `finite:`).
- **One screen**: a colour tile per item in place of artwork, play/pause through `playPauseActionFor`, stop, a seek bar for the finite item, a state line (player state, playWhenReady, isPlaying, suppression, recovery state, generation) and the event log.
- **The event log**: every player callback and every `RecoveryEvent`, with time, media id and generation. Lines that `FailureReportDeduper` would report are marked `[report once]`; a give-up also shows a toast every time.
- **The notification**: Media3's defaults (`DefaultMediaNotificationProvider`), with `foregroundServiceType="mediaPlayback"`. On Android 13 and later the app explains and asks for `POST_NOTIFICATIONS` once.
- **Low volume**: the player volume is 0.2 and the tones are short beeps at about -21 dBFS.

### Fixtures

All audio is generated by `FixtureCatalog` (PCM WAV, mono, 8 kHz, 16-bit), so it falls under this repository's licence. A unit test fails if a hash moves.

| Item | Path | Content | Size | SHA-256 |
| --- | --- | --- | --- | --- |
| Finite tone | `/finite/tone-30s.wav` | 523 Hz for 250 ms each second, 30 s | 480,044 bytes | `79c2f778...35191` |
| Live-like A | `/live/live-a.wav` | 440 Hz for 150 ms each second, endless | unbounded | one-second loop `d9339178...8d531` |
| Live-like B | `/live/live-b.wav` | 660 Hz for 150 ms each second, endless | unbounded | one-second loop `a7112610...34001` |

Each live stream also has a mirror path (`/live/live-a-mirror.wav`) with the same bytes and its own scenario.

The server binds 127.0.0.1 on an ephemeral port and reuses that port on the next launch. It answers GET and HEAD, and single `Range` requests on the finite file (206, 416). The live paths send a WAV header that declares the largest data size a RIFF header can hold (4,294,967,258 bytes), then loop the tone with no `Content-Length`. They keep 2 seconds ahead of real time and then write at real-time pace. `Range: bytes=N-` on a live path is answered with 206 from offset N, and the bytes at an offset never change, so a reconnecting load resumes the same audio.

### What ExoPlayer does with the live-like stream

Checked against Media3 1.9.2:

- **It is not live to ExoPlayer**: `WavExtractor` takes the duration from the declared data size, and `ProgressiveMediaPeriod` only marks a progressive item live when its length is unknown and the seek map has no duration. So ExoPlayer sees a seekable item about 74.6 hours long, and `isCurrentMediaItemLive` is false.
- **Recovery does not care**: it asks the host for the item kind, never ExoPlayer, so these streams get the live ladder: re-prepare from position 0 on a 1 s doubling backoff. Position 0 opens a new connection, which is the "live edge" here.
- **Media3 retries first**: HTTP connect and read timeouts are 8 s by default. A failed load is retried by Media3's own load error policy before recovery sees anything, and the retry count resets whenever the load made progress. Recovery acts when Media3 surfaces `onPlayerError` or a watchdog fires.
- **A clean close ends the item**: with no `Content-Length`, the close is the end of the body. The player reaches ENDED, which recovery treats as a live failure (`state_ended`) and re-prepares.

### Developer panel

Debug builds only (`BuildConfig.DEBUG`), opened from "Developer" on the screen. Server switches apply to the item chosen at the top of the panel, from its next request. The last column is what the recovery rules call for; the event log shows what actually happened.

| Switch | What changes | Expected recovery behaviour |
| --- | --- | --- |
| Delay first byte 5 s | The status line comes 5 s late | None expected: under the 8 s read timeout and the 15 s buffering watchdog |
| Delay first byte 20 s | The status line comes 20 s late, on every request | Live: the 15 s buffering watchdog (`buffer_timeout`) or a surfaced load error starts the ladder. Finite: slow buffering never spends the budget; a surfaced error gets up to three retries at the retained position |
| Stall 20 s / 60 s after 3 s | The server stops writing for that long, connection open | The player drains its lead and buffers. Media3's read timeout reconnects; on a live item the buffering watchdog starts the ladder if the gap lasts 15 s |
| Disconnect after 3 s | The connection is reset (TCP RST) after 3 s of audio | Media3 resumes the load at the offset; recovery steps in only if that keeps failing |
| End stream after 5 s | Live paths: the body ends cleanly after 5 s | ENDED on a live item is a failure: a rung re-prepares from 0 |
| HTTP 404 | Every request gets 404 | Permanent: a live item stops at once and reports (`GaveUp`, `permanent=true`), a finite item gives up without retrying. With "replace source URI" on, a live item retries on its mirror instead |
| HTTP 500 | Every request gets 500 | Retryable: the live ladder climbs (1 s, 2 s, 4 s ... 30 s) and reports at the threshold; a finite item gives up after three attempts |
| Fail once, then serve again | The next scenario applies to one response, then the server is normal | Reconnect: the next rung succeeds, the log shows the ladder resetting after 30 s of stable playback |
| Reset target / Reset all | Back to normal | |
| Finite EOF | Seeks the finite item to 5 s before its end | The end of a finite item is not a failure: no rung, no budget spent |
| Rapid reselect | Selects the playing item twice in a row | Each selection is a new intent: a generation bump per selection, and no rung from the first one runs after the second |
| Simulate offline | `hasNetwork()` answers false | A failure waits for the network (`AwaitingNetwork`) instead of a rung. Turning it off signals an arrival, which resumes through the next rung |
| Simulate suppression 10 s | The app takes transient audio focus for 10 s | Suppression is not a stall: no watchdog fires, a due rung waits (`WaitingForSuppression`) |
| Sleep veto | `automaticStartAllowed()` answers false | Recovery starts nothing on its own; only play does |
| Sleep now | Veto on, `cancelAutomaticWork()`, pause | The documented host stop: pending work is cancelled and nothing restarts |
| Replace source URI | `retrySourceFor` answers the live item's mirror | A failing live item is retried on another URI, media id kept |

The panel also shows each path's scenario and request count, the server address, the build's commit and the fixture hash.

### What the sample does not prove

- **No real network**: the loopback server keeps working when the device is offline, so the network wait is exercised through the host predicate only. Real handovers, captive portals and slow radio links are not covered.
- **No real live stream**: the live-like stream is a long seekable WAV to ExoPlayer, not an HLS or Icecast stream, and a test against those is still needed.
- **Logs are not listening**: a correct log does not prove the audio sounded right. Listen to it.
- **Release builds cannot play**: the cleartext exception for 127.0.0.1 lives in the debug source set only, so a release build of the sample cannot reach its own server, and the developer panel is not shown.

## Requirements

- Android API 24+
- Media3 1.9.2

## License

MIT. See [LICENSE](LICENSE).
