package es.citaasistente.app.net

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import java.io.FileOutputStream

/** Captura de lo que se ve en pantalla (la confirmación de la cita). Se llama en el hilo principal. */
object ScreenShot {

    fun capture(view: View, dir: File, name: String): File? {
        try {
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return null

            dir.mkdirs()

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))

            val file = File(dir, "$name.jpg")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()

            return file
        } catch (e: Throwable) {
            return null
        }
    }
}
