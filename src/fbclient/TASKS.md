# fbclient — TUI Parity Tasks

## All Done

- [x] Native device backends (fbdev, evdev, LEDs)
- [x] Vendored FreeType, tinyalsa, libopus
- [x] Full audio pipeline (ALSA capture → Opus → Ogg → Matrix upload)
- [x] Playback pipeline (Matrix download → Ogg demux → Opus decode → ALSA)
- [x] PTT with recording overlay
- [x] Read receipts (on conversation entry + Enter on message)
- [x] Settings applet with audio echo test, brightness, device info
- [x] Action dispatch (UI → sync thread)
- [x] Session persistence (config.json — store/restore access_token)
- [x] Display name (get/set via Matrix API, preset picker in settings)
- [x] Message deletion (F2 on message → redact event)
- [x] Family room (detect by #family: alias, show in contacts, group messages)
- [x] Self display name resolution from room membership
