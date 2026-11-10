# FlaminGo Features

* **Proxy Checking:** The app checks the availability of MTProto proxies for Telegram.
* **Active Sieve:** Fast links are checked first (1 sec), then slow ones (3 sec).
* **Fast Only:** A filter that cuts off proxies with ping above 150 ms.
* **Custom Lists:** You can load your own .txt file with a proxy list.
* **Settings:** Change the official list URL and check depth.
* **Direct Send:** The ✈️ button instantly adds a proxy to Telegram.
* **Network Splitting:** Lists are saved separately for each Wi-Fi network and carrier.
* **ReCheck (↻):** The arrow button allows you to quickly re-verify only the already found working links without downloading the whole list again.

---
### ⚠️ Important Information (Disclaimer)
* **Link availability is variable:** Even if a proxy was working 5 minutes ago, it may stop working. This depends on your ISP (DPI blocks), current server load, time of day, and other network restrictions.
* **How it works:** The app only checks the technical ability to establish a TCP connection with the server (whether the port is open). It does NOT guarantee that Telegram traffic will pass through this proxy *right now*.
* **Recommendation:** Try using several different working links from the list one by one if one of them stops connecting.

---
* **Source code:** [github.com/mrDarkDuck/flaminGo](https://github.com/mrDarkDuck/flaminGo)
