# playback-kit

Playback recovery for Media3 players on Android. It keeps a player going through the failures a streaming app actually meets: dropped connections, servers that stop answering, streams that stall in READY, and a device that goes offline and comes back.

Media3 already retries each failed load. playback-kit is the outer loop that runs after Media3 gives up, and it knows that a live item and a finite item recover differently.

Nothing is published yet. Build it from source.

## What it does

- **Live items**: re-prepares from the live edge on a backoff that starts at one second and doubles to a 30 second ceiling. A permanent failure (a 404, an unsupported format) stops the player instead of spinning, and is reported at once.
- **Finite items**: re-prepares at the retained position, up to three attempts. Slow buffering and the end of the item never spend that budget.
- **Watchdogs**: a buffering timeout for live items and a stall check for a position that stops moving while READY.
- **Suppression**: a call or a navigation prompt never counts as a stall and never spends a retry. A due retry waits until the suppression lifts.
- **Pauses**: a pause the listener asked for, a headset unplug, a remote pause or a permanent focus loss ends recovery. A delayed retry re-checks play intent before it runs.
- **Network**: offline failures wait for a network arrival instead of retrying blind. Arrivals can be signalled from any thread.
- **Stable playback**: the ladder resets only after playback has run for 30 seconds, never on a READY blip.

## Usage

Attach on the player's application looper, after the player is built:

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
- **`onEvent`**: everything recovery did, as a `RecoveryEvent`.

### Events and reporting

`GaveUp` is emitted on every give-up, so the listener can be told each time. For analytics, feed events through `FailureReportDeduper` to report once per incident: the incident stays open across a stable stretch and closes on a real item transition.

### Same-id replaces

Wrap any in-place `replaceMediaItem` you make for the item that is playing (a metadata or artwork refresh) in `recovery.runHostReplace { }`. Choosing the item that is already playing with `setMediaItems` is a real restart and must not be wrapped.

### Listener ordering

`attach` registers its listener on the player. If your app must handle the end of an item before recovery sees ENDED, pass `registerListener = false` and forward `recovery.listener` callbacks from your own listener in the order you choose.

## Requirements

- Android API 24+
- Media3 1.9.2

## License

MIT. See [LICENSE](LICENSE).
