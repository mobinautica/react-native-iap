package com.dooboolab.rniap

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.ReactApplicationContext

class RNIapActivityListener(
    private val reactContext: ReactApplicationContext,
    private val onDeepLink: (Uri) -> Unit
) : ActivityEventListener {
    
    companion object {
        private const val TAG = "RNIapActivityListener"
        private const val RUSTORE_SCHEME = "rniap"
    }
    
    init {
        reactContext.addActivityEventListener(this)
    }
    
    override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
        // RuStore SDK handles activity results internally
        // This method is kept for compatibility
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
    }
    
    override fun onNewIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == RUSTORE_SCHEME) {
                Log.d(TAG, "Received RuStore deep link: $uri")
                onDeepLink(uri)
            }
        }
    }
    
    fun cleanup() {
        reactContext.removeActivityEventListener(this)
    }
}