package com.prf.security.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * The reason "No Camera Available on This Device" appeared on old and deprecated phones:
 * the app trusted [CameraSelector.requireLensFacing] alone. On devices whose Camera2 HAL
 * is broken or that simply do not advertise the feature flag, that call throws
 * IllegalArgumentException and the whole capture flow died.
 *
 * This object answers one question defensively - "is there ANY usable camera here?" - by
 * checking, in order of reliability:
 *   1. Camera1 [Camera.getNumberOfCameras], which works back to API 1 and is the only
 *      honest answer on hardware where the Camera2 HAL is broken.
 *   2. The PackageManager feature flags.
 *   3. CameraX [ProcessCameraProvider.hasCamera], a non-throwing check.
 *
 * Callers then bind only lenses that were confirmed present, and degrade to whatever the
 * device actually offers. A device with no camera at all still installs and still records
 * a check-in, because manifest declares every camera feature as required="false".
 */
object CameraCompat {

    private const val TAG = "CameraCompat"

    /** True if this device offers any camera sensor at all. */
    fun hasAnyCamera(context: Context): Boolean = bestLens(context) != null

    /**
     * The lens the app should bind, or null if the device truly has no camera.
     * Prefers BACK (documents/scene), then FRONT, then "whatever Camera1 reports" which
     * covers deprecated single-sensor devices that carry no facing flag at all.
     */
    fun bestLens(context: Context): Int? {
        val camera1Count = camera1Count()
        val pm = context.packageManager

        val hasBackFeature = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
            pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasFrontFeature = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)

        val cameraXBack = runCatching {
            ProcessCameraProvider.getInstance(context).get().hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        }.getOrDefault(false)
        val cameraXFront = runCatching {
            ProcessCameraProvider.getInstance(context).get().hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        }.getOrDefault(false)

        val back = cameraXBack || hasBackFeature || camera1Count > 0
        val front = cameraXFront || hasFrontFeature || camera1Count > 1

        return when {
            back -> CameraSelector.LENS_FACING_BACK
            front -> CameraSelector.LENS_FACING_FRONT
            camera1Count > 0 -> CameraSelector.LENS_FACING_BACK
            else -> null
        }.also {
            Log.i(
                TAG,
                "camera1Count=$camera1Count featureBack=$hasBackFeature featureFront=$hasFrontFeature " +
                    "cameraXBack=$cameraXBack cameraXFront=$cameraXFront -> lens=$it"
            )
        }
    }

    /**
     * Whether [lens] can actually be bound right now. Never throws - every check is
     * wrapped, and a broken Camera2 path falls back to the Camera1 sensor count.
     */
    fun lensAvailable(context: Context, lens: Int): Boolean {
        runCatching {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val sel = CameraSelector.Builder().requireLensFacing(lens).build()
            return provider.hasCamera(sel)
        }.onFailure { Log.w(TAG, "hasCamera($lens) threw, using Camera1 count", it) }

        val n = camera1Count()
        return when (lens) {
            CameraSelector.LENS_FACING_BACK -> n > 0
            CameraSelector.LENS_FACING_FRONT -> n > 1
            else -> n > 0
        }
    }

    /** Total cameras reported by the legacy [android.hardware.Camera] API. Safe on API 23+. */
    @Suppress("DEPRECATION")
    private fun camera1Count(): Int = runCatching {
        Camera.getNumberOfCameras()
    }.getOrDefault(0)
}
