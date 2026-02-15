package il.co.tmg.screentool

import android.graphics.Rect
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DirtyRegionData(
    val rects: List<Rect>,
    val hasChanges: Boolean
) : Parcelable
