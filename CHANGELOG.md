## Version 1.101 (Stability)
* **Socket leak fixed:** The app will no longer exhaust system resources when checking hundreds of dead proxies.
* **"Stop" button hang fixed:** The stop button now correctly resets the UI and stops the background service.
* **Crash protection:** Eliminated potential crashes (NullPointerException) when receiving an empty list or closing the app.
* **Thread optimization:** The slow proxy queue now uses a thread-safe collection, speeding up processing.

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
