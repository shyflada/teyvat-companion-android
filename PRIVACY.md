# Android privacy

Teyvat Companion starts capture only after Android grants screen sharing.
Frames are processed in memory for localisation and chest detection. The
foreground notification can stop capture.

Public map data is downloaded from HoYoLAB over HTTPS. The app saves progress,
settings and pending map updates in its private storage. During optional
account sync, the app stops capture before opening the official map. HoYoLAB
handles sign-in and receives the selected pin updates. The app reads the
signed-in state and pin IDs. WebView keeps the browser session locally.

The repository and APK contain the app and its dependencies. Keep game
recordings, device data and signing keys outside the repository.
