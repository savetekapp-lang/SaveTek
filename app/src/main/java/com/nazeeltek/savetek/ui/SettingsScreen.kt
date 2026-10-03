package com.nazeeltek.savetek.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.filled.Email
import com.nazeeltek.savetek.AppConfig
import com.nazeeltek.savetek.UpdateManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.nazeeltek.savetek.LocaleHelper
import com.nazeeltek.savetek.R
import com.nazeeltek.savetek.data.DefaultQuality
import com.nazeeltek.savetek.data.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    defaultQuality: DefaultQuality,
    wifiOnly: Boolean,
    themeMode: ThemeMode,
    language: String,
    engineVersion: String?,
    engineUpdatedAt: Long,
    updating: Boolean,
    engineReady: Boolean,
    onDefaultQuality: (DefaultQuality) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onLanguage: (String) -> Unit,
    onUpdateEngine: () -> Unit,
    appVersion: String,
    availableUpdateVersion: String?,
    checkingUpdates: Boolean,
    onCheckUpdates: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    var showAbout by rememberSaveable { mutableStateOf(false) }
    if (showAbout) {
        BackHandler { showAbout = false }
        AboutScreen(onBack = { showAbout = false })
        return
    }
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.primary)

        // ── تحديثات التطبيق ──
        SettingsCard {
            SectionTitle(stringResource(R.string.app_updates))
            Text(stringResource(R.string.current_app_version, appVersion), color = colors.onSurface)
            Spacer(Modifier.height(4.dp))
            if (availableUpdateVersion != null) {
                // زر "تحديث التطبيق" مع نقطة حمراء عند توفر إصدار جديد
                BadgedBox(badge = { Badge() }, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onOpenUpdate, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_to, availableUpdateVersion))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            OutlinedButton(
                onClick = onCheckUpdates,
                enabled = !checkingUpdates,
                border = BorderStroke(1.dp, colors.primary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (checkingUpdates) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.primary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.checking_updates), color = colors.primary)
                } else {
                    Text(stringResource(R.string.check_updates), color = colors.primary)
                }
            }
        }

        // ── المظهر ──
        SettingsCard {
            SectionTitle(stringResource(R.string.appearance))
            ThemeMode.entries.forEach { mode ->
                ChoiceRow(selected = mode == themeMode, onClick = { onThemeMode(mode) }) {
                    ThemeSwatch(mode)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(themeName(mode)), color = colors.onSurface)
                        MutedText(stringResource(themeDescription(mode)))
                    }
                }
            }
        }

        // ── اللغة ──
        SettingsCard {
            SectionTitle(stringResource(R.string.language))
            ChoiceRow(selected = language.isEmpty(), onClick = { onLanguage("") }) {
                Text(stringResource(R.string.language_system), color = colors.onSurface)
            }
            LocaleHelper.SUPPORTED.forEach { tag ->
                ChoiceRow(selected = language == tag, onClick = { onLanguage(tag) }) {
                    // اسم كل لغة يُكتب بلغتها نفسها حتى يجدها صاحبها بسهولة
                    Text(stringResource(languageName(tag)), color = colors.onSurface)
                }
            }
        }

        // ── الجودة الافتراضية ──
        SettingsCard {
            SectionTitle(stringResource(R.string.default_quality))
            MutedText(stringResource(R.string.default_quality_desc))
            Spacer(Modifier.height(4.dp))
            DefaultQuality.entries.forEach { q ->
                ChoiceRow(selected = q == defaultQuality, onClick = { onDefaultQuality(q) }) {
                    Text(qualityName(q), color = colors.onSurface)
                }
            }
        }

        // ── الواي فاي فقط ──
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.wifi_only), fontWeight = FontWeight.Bold, color = colors.onSurface)
                    MutedText(stringResource(R.string.wifi_only_desc))
                }
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = onWifiOnly,
                    colors = SwitchDefaults.colors(checkedThumbColor = colors.onPrimary, checkedTrackColor = colors.primary),
                )
            }
        }

        // ── محرك التحميل ──
        SettingsCard {
            SectionTitle(stringResource(R.string.engine_title))
            val versionText = engineVersion
                ?: stringResource(if (engineReady) R.string.unknown else R.string.preparing)
            Text(stringResource(R.string.engine_version, versionText), color = colors.onSurface)
            Text(stringResource(R.string.engine_last_update, formatUpdateDate(engineUpdatedAt)), color = colors.onSurface)
            MutedText(stringResource(R.string.engine_auto_desc))
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onUpdateEngine,
                enabled = engineReady && !updating,
                border = BorderStroke(1.dp, colors.primary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (updating) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.primary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.updating), color = colors.primary)
                } else {
                    Text(stringResource(R.string.update_engine), color = colors.primary)
                }
            }
        }

        // ── الإشعارات (تظهر هذه البطاقة فقط إذا كانت الإشعارات معطّلة) ──
        val context = LocalContext.current
        // نعيد الفحص كلما عاد المستخدم للتطبيق (مثلاً بعد تفعيلها من إعدادات الجوال)
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val notificationsOn = remember(lifecycleState) {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        if (!notificationsOn) {
            SettingsCard {
                SectionTitle(stringResource(R.string.notifications_off_title))
                MutedText(stringResource(R.string.notifications_off_desc))
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { openNotificationSettings(context) },
                    border = BorderStroke(1.dp, colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.enable_notifications), color = colors.primary) }
            }
        }

        // ── عن التطبيق ──
        SettingsCard(onClick = { showAbout = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, tint = colors.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.about),
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // سهم يتجه تلقائياً حسب اتجاه اللغة
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.primary)
            }
        }
    }
}

/** صف اختيار بزر دائري. */
@Composable
private fun ChoiceRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        content()
    }
}

/** دائرة صغيرة تُظهر لوني الخلفية والذهبي لكل مظهر. */
@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    val (bg, accent) = when (mode) {
        ThemeMode.NAVY_GOLD -> Color(0xFF1E2A47) to Color(0xFFD4A853)
        ThemeMode.LIGHT -> Color.White to Color(0xFF8F6A16)
        ThemeMode.DARK -> Color.Black to Color(0xFFD4A853)
        ThemeMode.SYSTEM -> Color(0xFF808080) to Color(0xFFD4A853)
    }
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
    }
}

@StringRes
private fun themeName(mode: ThemeMode) = when (mode) {
    ThemeMode.NAVY_GOLD -> R.string.theme_navy_gold
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.SYSTEM -> R.string.theme_system
}

@StringRes
private fun themeDescription(mode: ThemeMode) = when (mode) {
    ThemeMode.NAVY_GOLD -> R.string.theme_navy_gold_desc
    ThemeMode.LIGHT -> R.string.theme_light_desc
    ThemeMode.DARK -> R.string.theme_dark_desc
    ThemeMode.SYSTEM -> R.string.theme_system_desc
}

@StringRes
private fun languageName(tag: String) = when (tag) {
    "ar" -> R.string.lang_ar
    "en" -> R.string.lang_en
    "ur" -> R.string.lang_ur
    "tr" -> R.string.lang_tr
    "fr" -> R.string.lang_fr
    "es" -> R.string.lang_es
    "id" -> R.string.lang_id
    "hi" -> R.string.lang_hi
    else -> R.string.lang_en
}

@Composable
private fun qualityName(q: DefaultQuality): String = when (q) {
    DefaultQuality.BEST -> stringResource(R.string.quality_best)
    DefaultQuality.AUDIO -> stringResource(R.string.quality_mp3)
    else -> "${q.maxShortSide}p"
}

@Composable
private fun SettingsCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/** تاريخ آخر تحديث بلغة التطبيق. */
@Composable
private fun formatUpdateDate(time: Long): String {
    if (time <= 0) return stringResource(R.string.never_updated)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return SimpleDateFormat("d MMMM yyyy, h:mm a", locale).format(Date(time))
}

/** يفتح صفحة إشعارات التطبيق في إعدادات الجوال. */
private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent) }
}

/**
 * يفتح تطبيق البريد برسالة جاهزة: العنوان "SaveTek - استفسار"،
 * وفي نهايتها رقم إصدار التطبيق وإصدار أندرويد ونوع الجوال تلقائياً.
 */
private fun sendContactEmail(context: Context) {
    val version = "${UpdateManager.currentVersionName(context)} (${UpdateManager.currentVersionCode(context)})"
    val android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val device = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    val subject = context.getString(R.string.contact_subject)
    val body = context.getString(R.string.contact_body, version, android, device)

    // نضع العنوان والنص في الرابط وفي الإضافات معاً، لأن بعض تطبيقات البريد تقرأ أحدهما فقط
    val mailto = "mailto:${AppConfig.CONTACT_EMAIL}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailto))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(AppConfig.CONTACT_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.no_email_app), Toast.LENGTH_LONG).show()
    }
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = colors.primary)
            }
            Text(stringResource(R.string.about), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.primary)
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = colors.primary)
            Text(stringResource(R.string.version_label, appVersion(context)), color = colors.onBackground)
            MutedText(stringResource(R.string.tagline))
        }

        SettingsCard {
            SectionTitle("⚠️ " + stringResource(R.string.disclaimer_title))
            Text(stringResource(R.string.disclaimer), color = colors.onSurface)
        }

        SettingsCard {
            SectionTitle(stringResource(R.string.open_source_title))
            Text(stringResource(R.string.open_source_text), color = colors.onSurface)
        }

        // ── تواصل معنا: يفتح تطبيق البريد برسالة جاهزة ──
        SettingsCard(onClick = { sendContactEmail(context) }) {
            SectionTitle(stringResource(R.string.contact_title))
            MutedText(stringResource(R.string.contact_desc))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Email, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(AppConfig.CONTACT_EMAIL, color = colors.primary, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            stringResource(R.string.copyright),
            color = colors.onBackground.copy(alpha = 0.7f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
