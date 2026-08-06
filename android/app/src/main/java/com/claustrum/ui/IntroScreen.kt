package com.claustrum.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.claustrum.ui.theme.ClaustrumTheme
import kotlinx.coroutines.launch

private data class IntroPage(val eyebrow: String, val title: String, val body: String)

private val PAGES = listOf(
    IntroPage(
        "01 · 機器之眼",
        "相機是主動防護的守護者",
        "不是事後才調閱的錄影機。claustrum 把鏡頭變成一隻持續警戒的機器之眼,即時理解畫面裡正在發生的事。",
    ),
    IntroPage(
        "02 · Edge AI",
        "運算全在裝置端完成",
        "L0 變化閘控先濾掉靜止畫面(省下絕大多數運算),只有場景改變時才喚醒 L1 場景描述。影格用完即刪、不離開裝置。",
    ),
    IntroPage(
        "03 · 即時告警",
        "偵測到危險,立刻示警",
        "社區跌倒通報保全、幼兒園衝突聲光警示。只有文字描述與事件會外傳,不含人物身分特徵。",
    ),
)

/**
 * First-run onboarding — three swipeable pages framing the product thesis, then
 * 「開始守護」 into the Live Monitor. Skippable. Shown once (caller persists the flag).
 */
@Composable
fun IntroScreen(onFinish: () -> Unit) {
    val c = ClaustrumTheme.colors
    val pager = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()
    val onLast = pager.currentPage == PAGES.lastIndex

    Column(
        Modifier.fillMaxSize().background(c.ground).statusBarsPadding().navigationBarsPadding(),
    ) {
        // Skip
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                "略過",
                color = c.faint, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(8.dp))
                    .clickable { onFinish() }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
        ) { i ->
            val page = PAGES[i]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ClaustrumMark(size = 96.dp, active = true)
                Spacer(Modifier.height(40.dp))
                Text(page.eyebrow, color = c.steel, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(14.dp))
                Text(
                    page.title, color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center, lineHeight = 32.sp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    page.body, color = c.muted, fontSize = 15.sp, lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Dots
        Row(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(PAGES.size) { i ->
                val active = i == pager.currentPage
                val w by animateDpAsState(if (active) 22.dp else 7.dp, label = "dotW")
                val col by animateColorAsState(if (active) c.accent else c.line, label = "dotC")
                Box(
                    Modifier.padding(horizontal = 4.dp).height(7.dp)
                        .size(width = w, height = 7.dp).clip(CircleShape).background(col),
                )
            }
        }

        // Primary action
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)
                .clip(RoundedCornerShape(14.dp)).background(c.accent)
                .clickable {
                    if (onLast) onFinish()
                    else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (onLast) "開始守護" else "下一步",
                color = c.onAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            )
        }
    }
}
