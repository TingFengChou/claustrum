package com.claustrum.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.claustrum.R
import com.claustrum.ui.theme.ClaustrumTheme
import kotlinx.coroutines.delay

/**
 * Splash — machine-eye Lottie boot animation + wordmark. Holds ~[holdMillis] then
 * calls [onDone]. The eye "opening" is the product thesis: the camera waking as a
 * guardian, not a passive recorder.
 */
@Composable
fun SplashScreen(holdMillis: Long = 2000L, onDone: () -> Unit) {
    val c = ClaustrumTheme.colors
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.machine_eye))

    var revealed by remember { mutableStateOf(false) }
    val wordmarkAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = LinearEasing),
        label = "wordmark",
    )

    LaunchedEffect(Unit) {
        delay(450)          // let the eye begin its first sweep
        revealed = true
        delay(holdMillis)
        onDone()
    }

    Box(
        Modifier.fillMaxSize().background(c.ground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LottieAnimation(
                composition = composition,
                iterations = LottieConstants.IterateForever,
                modifier = Modifier.size(148.dp),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                "CLAUSTRUM",
                color = c.ink,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                letterSpacing = 6.sp,
                modifier = Modifier.alpha(wordmarkAlpha),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "主動防護的即時守護者",
                color = c.muted,
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(wordmarkAlpha),
            )
        }
    }
}
