package com.nazeeltek.savetek.ads

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.nazeeltek.savetek.R

/**
 * مكان محجوز لإعلان في الواجهة.
 * ما دام AdsManager.ADS_ENABLED = false فهو لا يرسم شيئاً ولا يأخذ أي مساحة.
 */
@Composable
fun AdSlotView(slot: AdsManager.AdSlot, modifier: Modifier = Modifier) {
    if (!AdsManager.ADS_ENABLED) return

    // TODO: استبدل هذا المربع بإعلان حقيقي عند إضافة مكتبة الإعلانات.
    // المربع يوضح مكان الإعلان وحجمه (50dp ارتفاع البانر القياسي) أثناء التصميم.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.ad_space, slot.name),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
    }
}
