# Changelog

## Unreleased

- Added `PlaybackRecovery`: a live retry ladder (1 s doubling to 30 s), finite recovery at the retained position (three attempts), buffering and stall watchdogs, suppression-aware waiting, a thread-safe network-arrival handoff, and a ladder reset after 30 s of stable playback.
- Added `RecoveryHost` with seams for item kind, the network predicate, an automatic-start veto and a replacement URI for live items.
- Added the `RecoveryEvent` model, with `GaveUp` emitted on every give-up, and an optional `FailureReportDeduper` that reports once per incident.
- `RecoveryHost.onEvent` is delivered after the work that raised the event: the commands it decided on are issued and an announced rung is already cancellable. The callback is observational; hosts post player commands to a later looper turn.
- `PlaybackRecovery.attach` takes over a player that already holds a prepared item: its current item becomes the baseline and the watchdog for its state is armed.
- Added `RecoveryConfig`; every default is the timing the rules are tested against.
- Added the pure `RecoveryPolicy` rules and the transport rules (`playPauseActionFor`, `isEffectivelyPlaying`), with JVM tests.
- Added differential trace tests that run each scenario against a reference implementation and the library on a fake player and virtual clock, and require identical logs.
- Added a sample app: a host-owned media session service with recovery attached, generated finite and live-like fixtures on a loopback server, and a debug panel with a switch for each recovery scenario.
