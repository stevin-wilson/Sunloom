package com.sunloom.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight, zero-dependency Material-style 24dp vector icons for Sunloom.
 * Uses true cubic Bezier curves and EvenOdd fill rules for crisp rendering at all densities.
 */
object SunloomIcons {

    private fun icon24(
        name: String,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    val AutoAwesome: ImageVector = icon24("AutoAwesome") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 9f)
            lineTo(20.25f, 6.25f)
            lineTo(23f, 5f)
            lineTo(20.25f, 3.75f)
            lineTo(19f, 1f)
            lineTo(17.75f, 3.75f)
            lineTo(15f, 5f)
            lineTo(17.75f, 6.25f)
            close()
            moveTo(11.5f, 9.5f)
            lineTo(9f, 4f)
            lineTo(6.5f, 9.5f)
            lineTo(1f, 12f)
            lineTo(6.5f, 14.5f)
            lineTo(9f, 20f)
            lineTo(11.5f, 14.5f)
            lineTo(17f, 12f)
            close()
            moveTo(19f, 15f)
            lineTo(17.75f, 17.75f)
            lineTo(15f, 19f)
            lineTo(17.75f, 20.25f)
            lineTo(19f, 23f)
            lineTo(20.25f, 20.25f)
            lineTo(23f, 19f)
            lineTo(20.25f, 17.75f)
            close()
        }
    }

    val Block: ImageVector = icon24("Block") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            moveTo(4f, 12f)
            curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
            curveTo(13.85f, 4f, 15.55f, 4.63f, 16.9f, 5.69f)
            lineTo(5.69f, 16.9f)
            curveTo(4.63f, 15.55f, 4f, 13.85f, 4f, 12f)
            close()
            moveTo(12f, 20f)
            curveTo(10.15f, 20f, 8.45f, 19.37f, 7.1f, 18.31f)
            lineTo(18.31f, 7.1f)
            curveTo(19.37f, 8.45f, 20f, 10.15f, 20f, 12f)
            curveTo(20f, 16.42f, 16.42f, 20f, 12f, 20f)
            close()
        }
    }

    val CheckCircle: ImageVector = icon24("CheckCircle") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            moveTo(10f, 17f)
            lineTo(5f, 12f)
            lineTo(6.41f, 10.59f)
            lineTo(10f, 14.17f)
            lineTo(17.59f, 6.58f)
            lineTo(19f, 8f)
            lineTo(10f, 17f)
            close()
        }
    }

    val CloudDownload: ImageVector = icon24("CloudDownload") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 9f)
            horizontalLineTo(15f)
            verticalLineTo(3f)
            horizontalLineTo(9f)
            verticalLineTo(9f)
            horizontalLineTo(5f)
            lineTo(12f, 16f)
            close()
            moveTo(5f, 18f)
            verticalLineTo(20f)
            horizontalLineTo(19f)
            verticalLineTo(18f)
            close()
        }
    }

    val Collections: ImageVector = icon24("Collections") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(22f, 16f)
            verticalLineTo(4f)
            curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
            horizontalLineTo(8f)
            curveTo(6.9f, 2f, 6f, 2.9f, 6f, 4f)
            verticalLineTo(16f)
            curveTo(6f, 17.1f, 6.9f, 18f, 8f, 18f)
            horizontalLineTo(20f)
            curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
            close()
            moveTo(11f, 12f)
            lineTo(13.03f, 14.71f)
            lineTo(16f, 11f)
            lineTo(20f, 16f)
            horizontalLineTo(8f)
            lineTo(11f, 12f)
            close()
            moveTo(2f, 6f)
            verticalLineTo(20f)
            curveTo(2f, 21.1f, 2.9f, 22f, 4f, 22f)
            horizontalLineTo(18f)
            verticalLineTo(20f)
            horizontalLineTo(4f)
            verticalLineTo(6f)
            horizontalLineTo(2f)
            close()
        }
    }

    val DarkMode: ImageVector = icon24("DarkMode") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 3f)
            curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
            curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
            curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
            curveTo(21f, 11.54f, 20.96f, 11.08f, 20.9f, 10.64f)
            curveTo(19.92f, 12.01f, 18.32f, 12.9f, 16.5f, 12.9f)
            curveTo(13.52f, 12.9f, 11.1f, 10.48f, 11.1f, 7.5f)
            curveTo(11.1f, 5.68f, 11.99f, 4.08f, 13.36f, 3.1f)
            curveTo(12.92f, 3.04f, 12.46f, 3f, 12f, 3f)
            close()
        }
    }

    val Delete: ImageVector = icon24("Delete") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(6f, 19f)
            curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
            horizontalLineTo(16f)
            curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f)
            verticalLineTo(7f)
            horizontalLineTo(6f)
            verticalLineTo(19f)
            close()
            moveTo(8f, 9f)
            horizontalLineTo(16f)
            verticalLineTo(19f)
            horizontalLineTo(8f)
            verticalLineTo(9f)
            close()
            moveTo(15.5f, 4f)
            lineTo(14.5f, 3f)
            horizontalLineTo(9.5f)
            lineTo(8.5f, 4f)
            horizontalLineTo(5f)
            verticalLineTo(6f)
            horizontalLineTo(19f)
            verticalLineTo(4f)
            horizontalLineTo(15.5f)
            close()
        }
    }

    val DesktopWindows: ImageVector = icon24("DesktopWindows") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(21f, 2f)
            horizontalLineTo(3f)
            curveTo(1.9f, 2f, 1f, 2.9f, 1f, 4f)
            verticalLineTo(16f)
            curveTo(1f, 17.1f, 1.9f, 18f, 3f, 18f)
            horizontalLineTo(10f)
            verticalLineTo(20f)
            horizontalLineTo(8f)
            verticalLineTo(22f)
            horizontalLineTo(16f)
            verticalLineTo(20f)
            horizontalLineTo(14f)
            verticalLineTo(18f)
            horizontalLineTo(21f)
            curveTo(22.1f, 18f, 23f, 17.1f, 23f, 16f)
            verticalLineTo(4f)
            curveTo(23f, 2.9f, 22.1f, 2f, 21f, 2f)
            close()
            moveTo(21f, 16f)
            horizontalLineTo(3f)
            verticalLineTo(4f)
            horizontalLineTo(21f)
            verticalLineTo(16f)
            close()
        }
    }

    val Favorite: ImageVector = icon24("Favorite") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 21.35f)
            lineTo(10.55f, 20.03f)
            curveTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
            curveTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
            curveTo(9.24f, 3f, 10.91f, 3.81f, 12f, 5.09f)
            curveTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
            curveTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
            curveTo(22f, 12.28f, 18.6f, 15.36f, 13.45f, 20.04f)
            lineTo(12f, 21.35f)
            close()
        }
    }

    val FavoriteBorder: ImageVector = icon24("FavoriteBorder") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(16.5f, 3f)
            curveTo(14.76f, 3f, 13.09f, 3.81f, 12f, 5.09f)
            curveTo(10.91f, 3.81f, 9.24f, 3f, 7.5f, 3f)
            curveTo(4.42f, 3f, 2f, 5.42f, 2f, 8.5f)
            curveTo(2f, 12.28f, 5.4f, 15.36f, 10.55f, 20.04f)
            lineTo(12f, 21.35f)
            lineTo(13.45f, 20.03f)
            curveTo(18.6f, 15.36f, 22f, 12.28f, 22f, 8.5f)
            curveTo(22f, 5.42f, 19.58f, 3f, 16.5f, 3f)
            close()
            moveTo(12.1f, 18.55f)
            lineTo(12f, 18.65f)
            lineTo(11.9f, 18.55f)
            curveTo(7.14f, 14.24f, 4f, 11.39f, 4f, 8.5f)
            curveTo(4f, 6.5f, 5.5f, 5f, 7.5f, 5f)
            curveTo(9.04f, 5f, 10.54f, 5.99f, 11.07f, 7.36f)
            horizontalLineTo(12.94f)
            curveTo(13.46f, 5.99f, 14.96f, 5f, 16.5f, 5f)
            curveTo(18.5f, 5f, 20f, 6.5f, 20f, 8.5f)
            curveTo(20f, 11.39f, 16.86f, 14.24f, 12.1f, 18.55f)
            close()
        }
    }

    val FolderOpen: ImageVector = icon24("FolderOpen") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(20f, 6f)
            horizontalLineTo(12f)
            lineTo(10f, 4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineTo(18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineTo(20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(8f)
            curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
            close()
            moveTo(20f, 18f)
            horizontalLineTo(4f)
            verticalLineTo(8f)
            horizontalLineTo(20f)
            verticalLineTo(18f)
            close()
        }
    }

    val History: ImageVector = icon24("History") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(13f, 3f)
            curveTo(8.03f, 3f, 4f, 7.03f, 4f, 12f)
            horizontalLineTo(1f)
            lineTo(4.89f, 15.89f)
            lineTo(4.96f, 16.03f)
            lineTo(9f, 12f)
            horizontalLineTo(6f)
            curveTo(6f, 8.13f, 9.13f, 5f, 13f, 5f)
            curveTo(16.87f, 5f, 20f, 8.13f, 20f, 12f)
            curveTo(20f, 15.87f, 16.87f, 19f, 13f, 19f)
            curveTo(11.07f, 19f, 9.32f, 18.21f, 8.06f, 16.94f)
            lineTo(6.64f, 18.36f)
            curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
            curveTo(17.97f, 21f, 22f, 16.97f, 22f, 12f)
            curveTo(22f, 7.03f, 17.97f, 3f, 13f, 3f)
            close()
            moveTo(12f, 8f)
            verticalLineTo(13f)
            lineTo(16.28f, 15.54f)
            lineTo(17f, 14.33f)
            lineTo(13.5f, 12.25f)
            verticalLineTo(8f)
            horizontalLineTo(12f)
            close()
        }
    }

    val Info: ImageVector = icon24("Info") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 20f)
            curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
            curveTo(4f, 7.59f, 7.59f, 4f, 12f, 4f)
            curveTo(16.41f, 4f, 20f, 7.59f, 20f, 12f)
            curveTo(20f, 16.41f, 16.41f, 20f, 12f, 20f)
            close()
            moveTo(11f, 7f)
            horizontalLineTo(13f)
            verticalLineTo(9f)
            horizontalLineTo(11f)
            close()
            moveTo(11f, 11f)
            horizontalLineTo(13f)
            verticalLineTo(17f)
            horizontalLineTo(11f)
            close()
        }
    }

    val LightMode: ImageVector = icon24("LightMode") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 7f)
            curveTo(9.24f, 7f, 7f, 9.24f, 7f, 12f)
            curveTo(7f, 14.76f, 9.24f, 17f, 12f, 17f)
            curveTo(14.76f, 17f, 17f, 14.76f, 17f, 12f)
            curveTo(17f, 9.24f, 14.76f, 7f, 12f, 7f)
            close()
            moveTo(11f, 1f)
            horizontalLineTo(13f)
            verticalLineTo(4f)
            horizontalLineTo(11f)
            close()
            moveTo(11f, 20f)
            horizontalLineTo(13f)
            verticalLineTo(23f)
            horizontalLineTo(11f)
            close()
            moveTo(3.51f, 4.92f)
            lineTo(4.92f, 3.51f)
            lineTo(7.05f, 5.64f)
            lineTo(5.64f, 7.05f)
            close()
            moveTo(16.95f, 18.36f)
            lineTo(18.36f, 16.95f)
            lineTo(20.49f, 19.08f)
            lineTo(19.08f, 20.49f)
            close()
            moveTo(1f, 11f)
            horizontalLineTo(4f)
            verticalLineTo(13f)
            horizontalLineTo(1f)
            close()
            moveTo(20f, 11f)
            horizontalLineTo(23f)
            verticalLineTo(13f)
            horizontalLineTo(20f)
            close()
            moveTo(3.51f, 19.08f)
            lineTo(5.64f, 16.95f)
            lineTo(7.05f, 18.36f)
            lineTo(4.92f, 20.49f)
            close()
            moveTo(16.95f, 5.64f)
            lineTo(19.08f, 3.51f)
            lineTo(20.49f, 4.92f)
            lineTo(18.36f, 7.05f)
            close()
        }
    }

    val MoreVert: ImageVector = icon24("MoreVert") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 8f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
            reflectiveCurveToRelative(-2f, 0.9f, -2f, 2f)
            reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
            close()
            moveTo(12f, 10f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
            reflectiveCurveToRelative(2f, -0.9f, 2f, -2f)
            reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
            close()
            moveTo(12f, 16f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
            reflectiveCurveToRelative(2f, -0.9f, 2f, -2f)
            reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
            close()
        }
    }

    val Pause: ImageVector = icon24("Pause") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 19f)
            horizontalLineTo(10f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(18f)
            verticalLineTo(5f)
            close()
        }
    }

    val PlayArrow: ImageVector = icon24("PlayArrow") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 5f)
            verticalLineTo(19f)
            lineTo(19f, 12f)
            close()
        }
    }

    val Refresh: ImageVector = icon24("Refresh") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
            curveTo(7.58f, 4f, 4.01f, 7.58f, 4.01f, 12f)
            curveTo(4.01f, 16.42f, 7.58f, 20f, 12f, 20f)
            curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f)
            horizontalLineTo(17.65f)
            curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
            curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
            curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
            curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
            lineTo(13f, 11f)
            horizontalLineTo(20f)
            verticalLineTo(4f)
            lineTo(17.65f, 6.35f)
            close()
        }
    }

    val Fullscreen: ImageVector = icon24("Fullscreen") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 14f); horizontalLineTo(5f); verticalLineTo(19f); horizontalLineTo(10f); verticalLineTo(17f); horizontalLineTo(7f); close()
            moveTo(5f, 10f); horizontalLineTo(7f); verticalLineTo(7f); horizontalLineTo(10f); verticalLineTo(5f); horizontalLineTo(5f); close()
            moveTo(17f, 17f); horizontalLineTo(14f); verticalLineTo(19f); horizontalLineTo(19f); verticalLineTo(14f); horizontalLineTo(17f); close()
            moveTo(14f, 5f); verticalLineTo(7f); horizontalLineTo(17f); verticalLineTo(10f); horizontalLineTo(19f); verticalLineTo(5f); close()
        }
    }

    val FullscreenExit: ImageVector = icon24("FullscreenExit") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 16f); horizontalLineTo(8f); verticalLineTo(19f); horizontalLineTo(10f); verticalLineTo(14f); horizontalLineTo(5f); close()
            moveTo(8f, 8f); horizontalLineTo(5f); verticalLineTo(10f); horizontalLineTo(10f); verticalLineTo(5f); horizontalLineTo(8f); close()
            moveTo(14f, 19f); horizontalLineTo(16f); verticalLineTo(16f); horizontalLineTo(19f); verticalLineTo(14f); horizontalLineTo(14f); close()
            moveTo(16f, 8f); verticalLineTo(5f); horizontalLineTo(14f); verticalLineTo(10f); horizontalLineTo(19f); verticalLineTo(8f); close()
        }
    }

    val Restore: ImageVector = icon24("Restore") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.5f, 8f)
            curveTo(9.85f, 8f, 7.45f, 9f, 5.6f, 10.6f)
            lineTo(2f, 7f)
            verticalLineTo(16f)
            horizontalLineTo(11f)
            lineTo(7.38f, 12.38f)
            curveTo(8.77f, 11.22f, 10.54f, 10.5f, 12.5f, 10.5f)
            curveTo(16.04f, 10.5f, 19.05f, 12.81f, 20.1f, 16f)
            lineTo(22.47f, 15.22f)
            curveTo(21.08f, 11.03f, 17.15f, 8f, 12.5f, 8f)
            close()
        }
    }

    val Schedule: ImageVector = icon24("Schedule") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(11.99f, 2f)
            curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.47f, 22f, 11.99f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 11.99f, 2f)
            close()
            moveTo(12f, 20f)
            curveTo(7.58f, 20f, 4f, 16.42f, 4f, 12f)
            curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
            curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
            curveTo(20f, 16.42f, 16.42f, 20f, 12f, 20f)
            close()
            moveTo(12.5f, 7f)
            horizontalLineTo(11f)
            verticalLineTo(13f)
            lineTo(16.25f, 16.15f)
            lineTo(17f, 14.92f)
            lineTo(12.5f, 12.25f)
            verticalLineTo(7f)
            close()
        }
    }

    val Security: ImageVector = icon24("Security") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 1f)
            lineTo(3f, 5f)
            verticalLineTo(11f)
            curveTo(3f, 16.55f, 6.84f, 21.74f, 12f, 23f)
            curveTo(17.16f, 21.74f, 21f, 16.55f, 21f, 11f)
            verticalLineTo(5f)
            lineTo(12f, 1f)
            close()
            moveTo(12f, 11.99f)
            horizontalLineTo(19f)
            curveTo(18.47f, 16.11f, 15.72f, 19.78f, 12f, 20.93f)
            verticalLineTo(12f)
            horizontalLineTo(5f)
            verticalLineTo(6.3f)
            lineTo(12f, 3.19f)
            verticalLineTo(11.99f)
            close()
        }
    }

    val SkipNext: ImageVector = icon24("SkipNext") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 18f)
            lineTo(14.5f, 12f)
            lineTo(6f, 6f)
            close()
            moveTo(16f, 6f)
            verticalLineTo(18f)
            horizontalLineTo(18f)
            verticalLineTo(6f)
            close()
        }
    }

    val SkipPrevious: ImageVector = icon24("SkipPrevious") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 6f)
            horizontalLineTo(8f)
            verticalLineTo(18f)
            horizontalLineTo(6f)
            close()
            moveTo(9.5f, 12f)
            lineTo(18f, 18f)
            verticalLineTo(6f)
            close()
        }
    }

    val ThumbDown: ImageVector = icon24("ThumbDown") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(15f, 3f)
            horizontalLineTo(6f)
            curveTo(5.17f, 3f, 4.46f, 3.5f, 4.16f, 4.22f)
            lineTo(1.14f, 11.27f)
            curveTo(1.05f, 11.5f, 1f, 11.74f, 1f, 12f)
            verticalLineTo(14f)
            curveTo(1f, 15.1f, 1.9f, 16f, 3f, 16f)
            horizontalLineTo(9.31f)
            lineTo(8.36f, 20.57f)
            lineTo(8.33f, 20.89f)
            curveTo(8.33f, 21.3f, 8.5f, 21.68f, 8.77f, 21.95f)
            lineTo(9.83f, 23f)
            lineTo(16.41f, 16.41f)
            curveTo(16.78f, 16.05f, 17f, 15.55f, 17f, 15f)
            verticalLineTo(5f)
            curveTo(17f, 3.9f, 16.1f, 3f, 15f, 3f)
            close()
            moveTo(15f, 15f)
            lineTo(10.66f, 19.34f)
            lineTo(11.77f, 14f)
            horizontalLineTo(3f)
            verticalLineTo(12f)
            lineTo(6f, 5f)
            horizontalLineTo(15f)
            verticalLineTo(15f)
            close()
            moveTo(19f, 3f)
            horizontalLineTo(23f)
            verticalLineTo(15f)
            horizontalLineTo(19f)
            close()
        }
    }

    val Visibility: ImageVector = icon24("Visibility") {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
            curveTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f)
            curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
            close()
            moveTo(12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
            close()
            moveTo(12f, 9f)
            curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
            close()
        }
    }
}
