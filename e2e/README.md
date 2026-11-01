# End-to-end checks

`./e2e/run.sh` builds Axis and the stand-in WhatsApp (`testapps/fakewa`), installs them on a connected phone or boots the
`axis_test` emulator, and runs about 30 checks through the real UI: onboarding, key validation, confirmations, messaging,
notifications (including an OTP and a prompt-injection message), setup, crash reports and key storage.

- Uses live Gemini with `GEMINI_KEY_1` from `../.env`. Checks that depend on the model can occasionally flake: re-run first.
- The stand-in installs an app named `com.whatsapp`. On a real phone, use a spare one, not your daily phone with real WhatsApp.
- Not covered: real WhatsApp/Instagram, speech, spoken output, OEM battery behavior.
