package com.nazeeltek.savetek.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.nazeeltek.savetek.R

/** لون خلفية صورة الشعار (نفس @color/splash_bg). */
val SplashBg = Color(0xFF11141E)

/** شاشة البداية: الشعار الكامل في المنتصف على خلفية بلونه. */
@Composable
fun SplashScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(SplashBg),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.splash_logo),
                contentDescription = stringResource(R.string.app_name),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
            // تدرّج خفيف أعلى الصورة وأسفلها حتى تذوب حوافها في الخلفية
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to SplashBg,
                            0.15f to Color.Transparent,
                            0.85f to Color.Transparent,
                            1f to SplashBg,
                        )
                    )
            )
        }
    }
}
