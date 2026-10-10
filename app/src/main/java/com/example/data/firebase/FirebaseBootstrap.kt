package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Connects Auth, Firestore, and Storage to the Firebase project in google-services.json.
 * Resource init is preferred. BuildConfig is the fallback when those resources are absent.
 */
object FirebaseBootstrap {
  private const val TAG = "FirebaseBootstrap"

  fun ensure(context: Context) {
    val appContext = context.applicationContext
    if (FirebaseApp.getApps(appContext).isNotEmpty()) return
    try {
      FirebaseApp.initializeApp(appContext)
    } catch (t: Throwable) {
      Log.w(TAG, "google-services resource init unavailable: ${t.message}")
    }
    if (FirebaseApp.getApps(appContext).isNotEmpty()) return

    val apiKey = BuildConfig.FIREBASE_API_KEY
    val projectId = BuildConfig.FIREBASE_PROJECT_ID
    val applicationId = BuildConfig.FIREBASE_APPLICATION_ID
    if (apiKey.isBlank() || projectId.isBlank() || applicationId.isBlank()) return

    val options = FirebaseOptions.Builder()
      .setApiKey(apiKey)
      .setApplicationId(applicationId)
      .setProjectId(projectId)
      .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
      .setGcmSenderId(BuildConfig.FIREBASE_GCM_SENDER_ID)
      .build()
    FirebaseApp.initializeApp(appContext, options)
  }
}
