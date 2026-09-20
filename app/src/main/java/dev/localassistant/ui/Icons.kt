package dev.localassistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Two icons the core Material set does not ship; the extended artifact is far too heavy for them. */
object AssistantIcons {
    val Mic: ImageVector = ImageVector.Builder(
        name = "Mic", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 14f)
            curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
            verticalLineTo(5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            reflectiveCurveTo(9f, 3.34f, 9f, 5f)
            verticalLineToRelative(6f)
            curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
            close()
            moveTo(17f, 11f)
            horizontalLineToRelative(-1.7f)
            curveTo(15.3f, 13f, 13.76f, 14.7f, 12f, 14.7f)
            reflectiveCurveTo(8.7f, 13f, 8.7f, 11f)
            horizontalLineTo(7f)
            curveToRelative(0f, 2.41f, 1.72f, 4.43f, 4f, 4.9f)
            verticalLineTo(19f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-3.1f)
            curveToRelative(2.28f, -0.47f, 4f, -2.49f, 4f, -4.9f)
            close()
        }
    }.build()

    val Stop: ImageVector = ImageVector.Builder(
        name = "Stop", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(7f, 7f)
            horizontalLineToRelative(10f)
            verticalLineToRelative(10f)
            horizontalLineToRelative(-10f)
            close()
        }
    }.build()
}
