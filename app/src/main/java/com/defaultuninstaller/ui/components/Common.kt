package com.defaultuninstaller.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.defaultuninstaller.App
import com.defaultuninstaller.data.safety.SafetyRating
import com.defaultuninstaller.ui.theme.safetyColor

@Composable
fun AppIcon(packageName: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val icons = (LocalContext.current.applicationContext as App).container.icons
    val bitmap by produceState<ImageBitmap?>(icons.cached(packageName), packageName) {
        value = icons.load(packageName)
    }
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun SafetyBadge(rating: SafetyRating, modifier: Modifier = Modifier) {
    Badge(safetyLabel(rating), safetyColor(rating), modifier)
}

fun safetyLabel(rating: SafetyRating) = when (rating) {
    SafetyRating.SAFE -> "Safe"
    SafetyRating.CAUTION -> "Caution"
    SafetyRating.CRITICAL -> "Critical"
    SafetyRating.UNKNOWN -> "Unknown"
}
