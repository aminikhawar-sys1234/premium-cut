package com.example.engine

import android.util.Log

/**
 * Loads libah_engine.so (OpenGL ES 3.0 native compositor built via CMake).
 *
 * Loading never throws: if the library is missing or fails to link (unsupported ABI,
 * stripped build, etc.) this returns false and the Kotlin/GLES pipeline stays in use.
 */
object NativeEngineLoader {
  private const val TAG = "NativeEngineLoader"
  private const val LIBRARY_NAME = "ah_engine"

  @Volatile private var isLoaded = false
  @Volatile private var isInitialized = false

  @Synchronized
  fun loadLibrary(): Boolean {
    if (isLoaded) return true
    isLoaded = try {
      System.loadLibrary(LIBRARY_NAME)
      true
    } catch (t: Throwable) {
      Log.w(TAG, "Native $LIBRARY_NAME not available, using Kotlin GPU pipeline", t)
      false
    }
    if (isLoaded && !isInitialized) {
      isInitialized = try {
        nativeInit()
      } catch (t: Throwable) {
        Log.w(TAG, "Native $LIBRARY_NAME init failed, using Kotlin GPU pipeline", t)
        false
      }
    }
    return isLoaded && isInitialized
  }

  fun isEngineLoaded(): Boolean = isLoaded
  fun isEngineInitialized(): Boolean = isInitialized

  private external fun nativeInit(): Boolean
}
