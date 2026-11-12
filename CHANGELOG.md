## Version 2.0.0 (Deep Update)
* **Deep MTProto Check:** The app now checks not just if the port is open (TCP), but sends a real cryptographic request. This filters out "dead" servers that are open but do not pass Telegram traffic.
* **New Versioning:** Transitioned to the V2.0.XXX format.
* **Improved Link Parsing:** Links with secrets are now parsed correctly.
* **Increased Stability:** Fixed socket leaks during checks.

## Version 1.100 (Major Update)
* **New UI:** The "What's New" button now only appears when there are unread updates.
* **Update Slider:** Update history is now displayed as swipeable slides from Settings.
* **Help Section:** Added a separate section describing application features.
* **Search Improvement:** "Fast proxies only" is now enabled by default.
* **Active Sieve:** Two-stage checking for maximum speed.

## Version 1.99
* **Settings:** Gear icon moved to the left corner.
* **Output Improvement:** Added a button to send proxies directly to Telegram (✈️).
* **Auto-update:** Fixed installation conflicts for new versions.
