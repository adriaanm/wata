package com.wata

import android.util.Log
import android.view.KeyEvent
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.bridge.ReactContext
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.facebook.react.modules.core.DeviceEventManagerModule

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "wata"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  /**
   * Log all key events for debugging hardware buttons
   */
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val keyCode = event.keyCode
    val action = when (event.action) {
      KeyEvent.ACTION_DOWN -> "DOWN"
      KeyEvent.ACTION_UP -> "UP"
      else -> "OTHER"
    }
    val keyName = KeyEvent.keyCodeToString(keyCode)

    Log.d("KeyEvent", "Key: $keyName ($keyCode) Action: $action")

    // Send to React Native
    sendKeyEventToJS(keyCode, action, keyName)

    return super.dispatchKeyEvent(event)
  }

  private fun sendKeyEventToJS(keyCode: Int, action: String, keyName: String) {
    val reactContext = reactInstanceManager?.currentReactContext
    if (reactContext != null && reactContext.hasActiveReactInstance()) {
      val params = com.facebook.react.bridge.Arguments.createMap().apply {
        putInt("keyCode", keyCode)
        putString("action", action)
        putString("keyName", keyName)
      }
      reactContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("onKeyEvent", params)
    }
  }
}
