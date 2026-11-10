# FlaminGo Features

* **Proxy Checking:** The app checks the availability of MTProto proxies for Telegram.
* **Active Sieve:** Fast links are checked first (1 sec), then slow ones (3 sec).
* **Fast Only:** A filter that cuts off proxies with ping above 150 ms.
* **Custom Lists:** You can load your own .txt file with a proxy list.
* **Settings:** Change the official list URL and check depth.
* **Direct Send:** The ✈️ button instantly adds a proxy to Telegram.
* **Network Splitting:** Lists are saved separately for each Wi-Fi network and carrier.
* **ReCheck (↻):** The arrow button allows you to quickly re-verify only the already found working links.

---
### ⚠️ Important Information (Disclaimer)
* **Link availability is variable:** A proxy can stop working at any time. This depends on your ISP (DPI blocks), server load, and time of day.
* **How it works:** The app only checks the TCP connection (whether the port is open). It does NOT guarantee that Telegram traffic will pass through this proxy *right now*.

---
### 🔒 Privacy and Permissions
We **do not collect, store, or share** any of your personal data. All settings and lists are saved locally on your device only.
The app requests the following permissions:
* **Location / Nearby Wi-Fi:** Solely so Android allows us to read the *name* of the Wi-Fi network (to split lists by network).
* **Phone State:** Only to read the mobile carrier name (to save lists separately for each SIM card).
* **Notifications:** To display the checking progress bar in the notification shade.
*(You can deny location and phone permissions. The app will still work, but it will save lists under generic names like "Wi-Fi" or "Mobile").*

---
---
* **Source code and releases:** [github.com/mrDarkDuck/flaminGo](https://github.com/mrDarkDuck/flaminGo)
* **Found a bug or have an idea?** Please open an **Issue** in the GitHub repository. This is the best way to contact the developer.
