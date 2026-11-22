package com.akslabs.circletosearch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val PREFS = "app_prefs"
private const val KEY_LAST_RUN_VERSION = "lastRunVersionCode"

/** Fonte única de verdade: delega para [isAccessibilityServiceEnabled]. */
fun isAccessibilityDropped(context: Context): Boolean = !isAccessibilityServiceEnabled(context)

/**
 * Compara o versionCode atual com o da última execução e atualiza o valor guardado.
 * Retorna true apenas na primeira execução após uma atualização (não na instalação inicial).
 */
fun markRunAndCheckUpdated(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val current = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        return false
    }
    val last = prefs.getLong(KEY_LAST_RUN_VERSION, -1L)
    prefs.edit().putLong(KEY_LAST_RUN_VERSION, current).apply()
    return last in 0 until current
}

/** Abre a tela de acessibilidade (detalhes do serviço no Android 13+, com fallback). */
fun openAccessibilitySettings(context: Context) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    if (Build.VERSION.SDK_INT >= 33) {
        try {
            val component = ComponentName(context, CircleToSearchAccessibilityService::class.java)
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra("android.intent.extra.COMPONENT_NAME", component.flattenToString())
                addFlags(flags)
            }
            context.startActivity(details)
            return
        } catch (_: Exception) {
            // literais: constantes so existem com compileSdk >= 33; cai no fallback abaixo
        }
    }
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(flags))
}

/**
 * Cartão de aviso exibido na tela inicial quando o serviço de acessibilidade está desligado.
 * Rechecka no ON_RESUME e some sozinho quando o serviço volta a estar ligado.
 */
@Composable
fun AccessibilityDroppedBanner(updatedThisRun: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var dropped by remember { mutableStateOf(isAccessibilityDropped(context)) }
    var dismissed by remember { mutableStateOf(false) }
    var wentToSettings by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowDropped = isAccessibilityDropped(context)
                if (dropped && !nowDropped && wentToSettings) {
                    Toast.makeText(context, context.getString(R.string.a11y_drop_done), Toast.LENGTH_SHORT).show()
                }
                if (!nowDropped) wentToSettings = false
                dropped = nowDropped
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!dropped || dismissed) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.a11y_drop_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (updatedThisRun) R.string.a11y_drop_body_updated else R.string.a11y_drop_body_generic
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { dismissed = true }) {
                    Text(stringResource(R.string.a11y_drop_later))
                }
                Button(onClick = {
                    wentToSettings = true
                    openAccessibilitySettings(context)
                }) {
                    Text(stringResource(R.string.a11y_drop_enable))
                }
            }
        }
    }
}
