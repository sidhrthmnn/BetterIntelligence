# Better Intelligence B icon

The static artwork is the user's supplied **Glowing Gradient B Chatbot Icon.png**. `bi-icon-original.png` preserves that source. `play-icon-512.png` is its opaque 512 × 512 Play listing export; `app/src/main/res/drawable-nodpi/bi_icon.png` and the `ic_bi_launcher` density variants are mechanically resized copies. The adaptive launcher uses the same artwork on black. No model generated the app's static identity.

The user submitted the Play listing icon update on 4 November 2026. At the final browser check, Google Play listed **Change app icon** under **Changes in review**. This repository change does not submit an app release.

## Animated chat avatar

The built-in image-generation tool produced `bi-avatar-base-original.png` from the supplied icon, removing just the two eye marks. Its resized Android copy is `app/src/main/res/drawable-nodpi/bi_avatar_base.png`.

Edit prompt:

> Use case: precise-object-edit. Edit target: the supplied square Glowing Gradient B Chatbot Icon. Create an animation base by removing ONLY the two small white curved eye marks and filling their locations with seamless matching dark navy face colour. Preserve the exact B silhouette, face outline, cyan/blue/purple/cream gradients, lighting, rounded-square tile, black outside, framing and alignment. No eyes, pupils or eyebrows; native code will animate the eyes. Do not add text, decoration or a new design. Return one square image matching the reference.

`BiAvatar.kt` draws the white eyes natively over that base, blinks every 4.2 seconds while the activity is resumed, and shifts their gaze down while an answer is being produced. It respects the system animation scale being zero. No continuous bitmap generation, network request or background animation is involved. The accessible name remains **Better Intelligence**, with a ready/answering state description.

The animated variant is used only inside chat. The launcher and Play listing use the unedited static icon.

Physical HONOR validation recorded the avatar while BI was preparing/generating an answer. `../2.5/screenshots/avatar-blink-frames.png` contains sequential crops from that recording, showing the open/closed eye frames. The complete answering-state recording is a local review artifact at `D:/Codex/bi-ui-review/avatar-answering.mp4`; it is not packaged in the app or uploaded to Google Play. Navigation screenshots also cover the icon in light, dark and AMOLED themes. No production chat content was recorded.
