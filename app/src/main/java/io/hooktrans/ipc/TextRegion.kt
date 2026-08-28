package io.hooktrans.ipc

import android.os.Parcel
import android.os.Parcelable

/**
 * One recognised line of text in an image, with its translation and where it sits.
 *
 * Coordinates are in the pixel space of the bitmap that was submitted for recognition, not in
 * view space. The hooked process is the only side that knows how that bitmap is finally
 * scaled, rotated or cropped onto the screen, so it does the mapping itself; the engine
 * process never needs to know how the image is displayed.
 *
 * [angleDeg] is the rotation ML Kit reports for the line. Text on a photographed sign is
 * rarely axis-aligned, and drawing a horizontal box over slanted text looks broken, so the
 * angle travels with the region and the overlay rotates to match.
 *
 * [bgColor] and [fgColor] are sampled from the image itself while the pixels are still in the
 * engine process, so the overlay can repaint a caption in something close to its original
 * colours instead of stamping a grey box on every picture.
 */
data class TextRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val source: String,
    val translated: String,
    val angleDeg: Float,
    val bgColor: Int = 0,
    val fgColor: Int = 0,
) : Parcelable {

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    constructor(p: Parcel) : this(
        left = p.readInt(),
        top = p.readInt(),
        right = p.readInt(),
        bottom = p.readInt(),
        source = p.readString().orEmpty(),
        translated = p.readString().orEmpty(),
        angleDeg = p.readFloat(),
        bgColor = p.readInt(),
        fgColor = p.readInt(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(left)
        dest.writeInt(top)
        dest.writeInt(right)
        dest.writeInt(bottom)
        dest.writeString(source)
        dest.writeString(translated)
        dest.writeFloat(angleDeg)
        dest.writeInt(bgColor)
        dest.writeInt(fgColor)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<TextRegion> {
            override fun createFromParcel(source: Parcel) = TextRegion(source)
            override fun newArray(size: Int) = arrayOfNulls<TextRegion>(size)
        }
    }
}
