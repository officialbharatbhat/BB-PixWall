package bb.pix.wall.web.runtime

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import bb.pix.wall.web.model.DisplayProfile

object DeviceDisplayProfile {
    fun detect(
        context: Context,
    ): DisplayProfile {
        val wm =
            context.getSystemService(
                Context.WINDOW_SERVICE
            ) as WindowManager

        val metrics =
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds =
                    wm.currentWindowMetrics.bounds

                DisplayMetrics().apply {
                    widthPixels = bounds.width()
                    heightPixels = bounds.height()
                    densityDpi =
                        context.resources
                            .displayMetrics
                            .densityDpi
                }
            } else {
                @Suppress("DEPRECATION")
                DisplayMetrics().also {
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(it)
                }
            }

        return DisplayProfile(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
        )
    }
}
