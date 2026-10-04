package com.nazeeltek.savetek.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nazeeltek.savetek.R
import com.nazeeltek.savetek.UpdateDialogState
import com.nazeeltek.savetek.UpdateManager
import com.nazeeltek.savetek.UpdatePhase

/**
 * نافذة تحديث التطبيق بكل مراحلها:
 * العرض (ما الجديد) ← التحميل ← إذن التثبيت ← التثبيت، أو رسالة خطأ.
 * في التحديث الإجباري لا يظهر زر "لاحقاً" ولا يمكن إغلاق النافذة.
 */
@Composable
fun UpdateDialog(
    state: UpdateDialogState,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
    onOpenPermission: () -> Unit,
    onInstall: () -> Unit,
    onOpenWebsite: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val language = LocalConfiguration.current.locales[0]?.language ?: "en"

    val title = when (state.phase) {
        UpdatePhase.PROMPT -> if (state.forced) R.string.update_required_title else R.string.update_available_title
        UpdatePhase.DOWNLOADING -> R.string.update_downloading_title
        UpdatePhase.NEED_PERMISSION -> R.string.install_permission_title
        UpdatePhase.READY -> R.string.update_ready_title
        UpdatePhase.ERROR -> R.string.update_error_title
    }

    AlertDialog(
        // التحديث الإجباري لا يُغلق بالضغط خارج النافذة أو زر الرجوع
        onDismissRequest = { if (!state.forced && state.phase != UpdatePhase.DOWNLOADING) onDismiss() },
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = colors.primary) },
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (state.phase) {
                    UpdatePhase.PROMPT -> {
                        Text(
                            stringResource(R.string.version_label, state.update.versionName),
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                        if (state.forced) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.update_required_text))
                        }
                        val notes = UpdateManager.whatsNewFor(state.update, language)
                        if (notes.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.whats_new), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            // "ما الجديد" قد يكون طويلاً: نجعله قابلاً للتمرير
                            Column(
                                Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                            ) { Text(notes) }
                        }
                    }
                    UpdatePhase.DOWNLOADING -> {
                        val p = state.progress
                        if (p == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.update_verifying))
                        } else {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = colors.primary)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.percent_value, (p * 100).toInt()))
                        }
                    }
                    UpdatePhase.NEED_PERMISSION -> Text(stringResource(R.string.install_permission_text))
                    UpdatePhase.READY -> Text(stringResource(R.string.update_ready_text))
                    UpdatePhase.ERROR -> Text(state.error?.asString() ?: "", color = colors.error)
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.PROMPT -> Button(onClick = onUpdateNow) { Text(stringResource(R.string.update_now)) }
                UpdatePhase.DOWNLOADING -> Unit
                UpdatePhase.NEED_PERMISSION -> Button(onClick = onOpenPermission) { Text(stringResource(R.string.open_settings)) }
                UpdatePhase.READY -> Button(onClick = onInstall) { Text(stringResource(R.string.install)) }
                // تعذّر قراءة التوقيع لن يتغير بإعادة المحاولة: نوجّه المستخدم للموقع
                UpdatePhase.ERROR -> if (state.offerWebsite) {
                    Button(onClick = onOpenWebsite) { Text(stringResource(R.string.open_website)) }
                } else {
                    Button(onClick = onUpdateNow) { Text(stringResource(R.string.retry)) }
                }
            }
        },
        dismissButton = {
            when {
                state.phase == UpdatePhase.DOWNLOADING ->
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = colors.primary) }
                state.forced -> Unit
                state.phase == UpdatePhase.PROMPT || state.phase == UpdatePhase.READY ->
                    TextButton(onClick = onLater) { Text(stringResource(R.string.later), color = colors.primary) }
                else ->
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = colors.primary) }
            }
        },
    )
}
