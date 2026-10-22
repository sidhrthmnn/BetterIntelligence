# Model library and downloads in 2.1.0-beta3

The model screen separates storage size from estimated inference RAM. A phone summary shows current available and total RAM. Each card gives a plain-language RAM result: enough available now, close apps to free RAM, or more RAM needed than the device can provide. Estimates match the existing load admission checks: GGUF starts at a small context; LiteRT uses the configured context, including fixed allocations and runtime floors. Full allocation reasons, quantization and publisher information remain under Details. A passing estimate is not an allocation guarantee; loading still performs its normal runtime checks.

Starting or resuming a download opens My models and brings the model into view once. Its position is pinned for that screen session through download, verification, pause and completion. Progress and changing free memory do not reorder cards or repeatedly take over scrolling. Incomplete catalogue downloads now appear in My models. The same model row is retained using its database ID.

Cards distinguish downloading, paused, interrupted, checking the file and downloaded states. Progress remains below 100% until successful verification and installation. Completed cards prominently say “Downloaded · Ready to use” and provide Use model, or In use for the active model. A model can remain downloaded while RAM is temporarily insufficient to load it. Downloading requires storage, not enough RAM to run the model.

Resuming initializes progress from the actual partial file rather than resetting it to 1%. If completion wins a race with Pause, the installed record is preserved. Existing HTTPS verification, checksums, cancellation, import, deletion and license prompts remain in place.

`ModelPresentationTest` checks lifecycle ordering, completion visibility, incomplete downloads and memory-pressure labels. The opt-in `ModelDownloadUiDeviceTest` downloads the public SmolLM2 135M package through the real Compose screen in the isolated validation app and checks the completed file and ready-to-use label.
