package com.prf.security.portal

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.prf.security.camera.CaptureActivity

/**
 * One hop between the HTML portal and the native capture flow. Exists so the portal can
 * start capture through a plain activity intent, without the WebView holding a reference
 * to the camera stack.
 */
class PhotoCheckInRouter : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, CaptureActivity::class.java))
        finish()
    }
}
