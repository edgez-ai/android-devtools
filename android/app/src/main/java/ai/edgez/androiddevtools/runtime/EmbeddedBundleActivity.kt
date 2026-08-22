package ai.edgez.androiddevtools.runtime

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.facebook.react.PackageList
import com.facebook.react.ReactHost
import com.facebook.react.defaults.DefaultReactHost
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import ai.edgez.androiddevtools.EdgezNativePackage

/** Loads the APK's index.android.bundle without Expo Dev Client or Metro. */
@Suppress("DEPRECATION")
class EmbeddedBundleActivity : AppCompatActivity(), DefaultHardwareBackBtnHandler {
  private lateinit var surface: ReactSurface

  private val embeddedHost: ReactHost by lazy {
    DefaultReactHost.getDefaultReactHost(
      context = applicationContext,
      packageList = PackageList(application).packages.apply { add(EdgezNativePackage()) },
      jsMainModulePath = "index",
      jsBundleAssetPath = "edgez-offgrid-map.android.bundle",
      useDevSupport = false
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.AppTheme)
    super.onCreate(savedInstanceState)
    surface = embeddedHost.createSurface(this, "main", null)
    setContentView(checkNotNull(surface.view) { "React surface did not create a view" })
    surface.start()
  }

  override fun onResume() {
    super.onResume()
    embeddedHost.onHostResume(this, this)
  }

  override fun onPause() {
    embeddedHost.onHostPause(this)
    super.onPause()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    embeddedHost.currentReactContext?.onNewIntent(this, intent)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    embeddedHost.currentReactContext?.onActivityResult(this, requestCode, resultCode, data)
  }

  @Deprecated("Deprecated in Android")
  override fun onBackPressed() {
    if (!embeddedHost.onBackPressed()) {
      invokeDefaultOnBackPressed()
    }
  }

  override fun invokeDefaultOnBackPressed() {
    super.onBackPressed()
  }

  override fun onDestroy() {
    surface.stop()
    embeddedHost.onHostDestroy(this)
    super.onDestroy()
  }
}
