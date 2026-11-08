# Model browser prototype

Open `index.html` in a browser. It is self-contained and works offline.

Try search, filters, sorting, model details, the RAM preview, light/dark appearance,
and download/pause/resume. Starting a download opens Downloads. Completed models
remain visible and also appear in My models. No model file is downloaded and no
search/device information is transmitted.

All device values, model sizes and RAM requirements are illustrative estimates.
This is a design prototype, not a model benchmark or compatibility certification.

Browser checks exercised search/details, download/pause/resume/completion, RAM
warnings and both appearances. Mobile layout was checked at 320 and 390 CSS pixels
without horizontal overflow. Progress updates retain the controls instead of
recreating them every tick.
