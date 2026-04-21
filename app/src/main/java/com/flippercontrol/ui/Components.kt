package com.flippercontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable
fun TopBar(
    title: String,
    color: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Text(
            "←",
            color = FlipperTheme.accent, fontSize = 20.sp,
            fontFamily = FlipperTheme.mono,
            modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp)
        )
        androidx.compose.material3.Text(
            title, color = color, fontSize = 20.sp,
            fontFamily = FlipperTheme.mono, fontWeight = FontWeight.Black,
            letterSpacing = 3.sp
        )
    }
}

@Composable
fun EmptyState(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(FlipperTheme.surface, RoundedCornerShape(10.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            message,
            color = FlipperTheme.textSecondary,
            fontSize = 12.sp,
            fontFamily = FlipperTheme.mono,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}
