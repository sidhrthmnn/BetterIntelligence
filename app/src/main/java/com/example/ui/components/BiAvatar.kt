package com.example.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import kotlinx.coroutines.delay

/** Only the eyes animate. No continuous spinner, bitmap regeneration or background work. */
@Composable
fun BiAvatar(answering: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val motionAllowed = remember(context) { runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true) }
    val eyeOpen = remember { Animatable(1f) }
    val gaze by animateFloatAsState(if (answering) 1f else 0f,
        animationSpec = tween(if (motionAllowed) 260 else 0), label = "avatar_gaze")
    LaunchedEffect(lifecycle, motionAllowed) {
        eyeOpen.snapTo(1f)
        if (motionAllowed && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            while (true) {
                delay(4200)
                eyeOpen.animateTo(.08f, tween(90))
                eyeOpen.animateTo(1f, tween(150))
            }
        }
    }
    Box(modifier.size(52.dp).testTag("bi_chat_avatar").semantics {
        contentDescription = "Better Intelligence"
        stateDescription = if (answering) "Looking down while answering" else "Ready"
    }) {
        Image(painterResource(if (MaterialTheme.colorScheme.background.luminance() > .5f) R.drawable.bi_avatar_light else R.drawable.bi_avatar_base), contentDescription = null, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val height = size.height * .060f * eyeOpen.value
            val y = size.height * (.449f + .025f * gaze) + size.height * .030f * (1 - eyeOpen.value)
            for (x in listOf(.428f, .548f)) {
                drawArc(Color.White, 202f, 136f, false,
                    topLeft = Offset(size.width * (x - .034f), y),
                    size = Size(size.width * .068f, height),
                    style = Stroke(size.width * .016f, cap = StrokeCap.Round))
            }
        }
    }
}
