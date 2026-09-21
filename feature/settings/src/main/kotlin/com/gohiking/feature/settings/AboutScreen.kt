package com.gohiking.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gohiking.core.designsystem.theme.GhColors
import com.gohiking.core.resources.R as CoreR

/**
 * 关于页（P-15，原型对齐）：品牌头（图标/名称/版本/描述）+ 仓库/许可/反馈卡。
 * 版本号运行时读取 PackageManager，避免 feature 模块依赖 app 的 BuildConfig。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionText = remember {
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("—")
    }
    val repoUrl = "https://github.com/hxzhang2000/GoHiking"
    val issueUrl = "$repoUrl/issues"

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GhColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // appbar：返回 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreR.string.common_action_back),
                    tint = GhColors.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(CoreR.string.about_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = GhColors.TextPrimary,
            )
        }

        // 品牌头
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Landscape,
                contentDescription = null,
                tint = GhColors.Primary,
                modifier = Modifier.size(60.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(CoreR.string.app_name),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = versionText, fontSize = 12.sp, color = GhColors.TextSecondary)
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(CoreR.string.about_desc),
                fontSize = 13.sp,
                color = GhColors.TextSecondary,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }

        Spacer(Modifier.height(22.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = GhColors.Surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Column {
                AboutRow(
                    title = stringResource(CoreR.string.about_repo),
                    subtitle = "github.com/hxzhang2000/GoHiking",
                    chevron = true,
                    onClick = { open(repoUrl) },
                )
                HorizontalDivider(color = GhColors.Line2, thickness = 1.dp)
                AboutRow(
                    title = stringResource(CoreR.string.about_license),
                    value = "Apache-2.0",
                )
                HorizontalDivider(color = GhColors.Line2, thickness = 1.dp)
                AboutRow(
                    title = stringResource(CoreR.string.about_issue),
                    chevron = true,
                    onClick = { open(issueUrl) },
                )
            }
        }

        Text(
            text = stringResource(CoreR.string.about_hint),
            fontSize = 12.sp,
            color = GhColors.TextSecondary,
            lineHeight = 19.sp,
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun AboutRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 12.sp, color = GhColors.TextSecondary)
            }
        }
        if (value != null) {
            Text(text = value, fontSize = 14.sp, color = GhColors.TextSecondary)
        }
        if (chevron) {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = GhColors.TextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}