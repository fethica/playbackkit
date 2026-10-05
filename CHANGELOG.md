# Changelog

## Unreleased

- Added `PlaybackRecovery`: a live retry ladder (1 s doubling to 30 s), finite recovery at the retained position (three attempts), buffering and stall watchdogs, suppression-aware waiting, a thread-safe network-arrival handoff, and a ladder reset after 30 s of stable playback.
- Added `RecoveryHost` with seams for item kind, the network predicate, an automatic-start veto and a replacement URI for live items.
- Added the `RecoveryEvent` model, with `GaveUp` emitted on every give-up, and an optional `FailureReportDeduper` that reports once per incident.
- Added `RecoveryConfig`; every default is the timing the rules are tested against.
- Added the pure `RecoveryPolicy` rules and the transport rules (`playPauseActionFor`, `isEffectivelyPlaying`), with JVM tests.
- Added differential trace tests that run each scenario against a reference implementation and the library on a fake player and virtual clock, and require identical logs.
