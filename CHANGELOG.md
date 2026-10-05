# Changelog

## Unreleased

- Added `PlaybackRecovery`: a live retry ladder (1 s doubling to 30 s), finite recovery at the retained position (three attempts), buffering and stall watchdogs, suppression-aware waiting, a thread-safe network-arrival handoff, and a ladder reset when a 30 s wall-clock window started at READY ends with the player playing.
- Added `RecoveryHost` with seams for item kind, the network predicate, an automatic-start veto and a replacement URI for live items.
- Added the `RecoveryEvent` model, with `GaveUp` emitted on every give-up, and an optional `FailureReportDeduper` that reports once per incident.
- `RecoveryHost.onEvent` is delivered after the work that raised the event: the commands it decided on are issued and an announced rung is already cancellable. The callback is observational; hosts post player commands to a later looper turn.
- A failure whose item the host chooses again (even with the same id), seeks or pauses inside `RecoveryHost.onBeforeAutomaticStart` at the failure's entry is dropped: nothing is paused, stopped, counted or reported for it. The check reads the player's getters, so a command that changes none of them (a seek to the current position while already IDLE) is not seen; a host issuing one there calls `cancelAutomaticWork()` first. The item token is compared by value, as a concatenated timeline returns a fresh but equal uid on every read.
- `PlaybackRecovery.attach` takes over a player that already holds a prepared item: its current item becomes the baseline and the watchdog for its state is armed.
- Added `RecoveryConfig`; every default is the timing the rules are tested against.
- Added the pure `RecoveryPolicy` rules and the transport rules (`playPauseActionFor`, `isEffectivelyPlaying`), with JVM tests.
- Added differential trace tests that run each scenario against a reference implementation and the library on a fake player and virtual clock. They compare a normalised log: per step, every player command, host action and state line in exact order, then the report and UI signals in exact order. The one intentional difference this allows is the terminal live failure, whose report the library delivers after its pause and stop rather than before.
- Added a sample app: a host-owned media session service with recovery attached, generated finite and live-like fixtures on a loopback server, and a debug panel with a switch for each recovery scenario.
