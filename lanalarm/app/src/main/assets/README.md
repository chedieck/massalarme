# Custom alarm sounds

This directory is empty on purpose. Massalarme ships no audio: sound files whose
licence nobody can name are the fastest way to get a repository taken down, and
what a siren should sound like is a matter of taste anyway.

Drop an MP3 here before you build and it is used. Leave the directory empty and
the app rings the phone's own alarm sound, which is what you get if you never
read this file.

| File | Played when |
|---|---|
| `custom-alarm.mp3` | A hard alarm rings — the one only the scale silences. |
| `custom-alarm-soft.mp3` | A soft alarm rings. Falls back to `custom-alarm.mp3`, then to the system sound. |
| `custom-bell.mp3` | A wrong passphrase is typed on the dismiss screen. Purely decorative; nothing happens without it. |

Both alarm files loop, so pick something that survives being repeated for six
minutes at full volume. Anything `MediaPlayer` can open works — MP3, OGG, WAV.
