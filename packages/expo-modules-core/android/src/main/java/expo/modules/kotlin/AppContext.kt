package expo.modules.kotlin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.UIManagerModule
import com.facebook.react.uimanager.common.UIManagerType
import expo.modules.adapters.react.NativeModulesProxy
import expo.modules.core.errors.ContextDestroyedException
import expo.modules.core.errors.ModuleNotFoundException
import expo.modules.core.interfaces.ActivityProvider
import expo.modules.interfaces.camera.CameraViewInterface
import expo.modules.interfaces.constants.ConstantsInterface
import expo.modules.interfaces.filesystem.AppDirectoriesModuleInterface
import expo.modules.interfaces.filesystem.FilePermissionModuleInterface
import expo.modules.interfaces.font.FontManagerInterface
import expo.modules.interfaces.imageloader.ImageLoaderInterface
import expo.modules.interfaces.permissions.Permissions
import expo.modules.interfaces.taskManager.TaskManagerInterface
import expo.modules.kotlin.activityresult.ActivityResultsManager
import expo.modules.kotlin.activityresult.DefaultAppContextActivityResultCaller
import expo.modules.kotlin.defaultmodules.ErrorManagerModule
import expo.modules.kotlin.defaultmodules.NativeModulesProxyModule
import expo.modules.kotlin.events.EventEmitter
import expo.modules.kotlin.events.EventName
import expo.modules.kotlin.events.KEventEmitterWrapper
import expo.modules.kotlin.events.KModuleEventEmitterWrapper
import expo.modules.kotlin.events.OnActivityResultPayload
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.providers.CurrentActivityProvider
import expo.modules.kotlin.tracing.trace
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.io.File
import java.lang.ref.WeakReference

class AppContext(
  modulesProvider: ModulesProvider,
  val legacyModuleRegistry: expo.modules.core.ModuleRegistry,
  reactContextHolder: WeakReference<ReactApplicationContext>
) : CurrentActivityProvider {

  // The main context used in the app.
  // Modules attached to this context will be available on the main js context.
  val hostingRuntimeContext = RuntimeContext(this, reactContextHolder)

  private val reactLifecycleDelegate = ReactLifecycleDelegate(this)

  private var hostWasDestroyed = false

  private val modulesQueueDispatcher = HandlerThread("expo.modules.AsyncFunctionQueue")
    .apply { start() }
    .looper.let { Handler(it) }
    .asCoroutineDispatcher()

  /**
   * A scope used to dispatch all background work.
   */
  val backgroundCoroutineScope = CoroutineScope(
    Dispatchers.IO +
      SupervisorJob() +
      CoroutineName("expo.modules.BackgroundCoroutineScope")
  )

  /**
   * A queue used to dispatch all async methods that are called via JSI.
   */
  val modulesQueue = CoroutineScope(
    modulesQueueDispatcher +
      SupervisorJob() +
      CoroutineName("expo.modules.AsyncFunctionQueue")
  )

  val mainQueue = CoroutineScope(
    Dispatchers.Main +
      SupervisorJob() +
      CoroutineName("expo.modules.MainQueue")
  )

  val registry
    get() = hostingRuntimeContext.registry

  internal var legacyModulesProxyHolder: WeakReference<NativeModulesProxy>? = null

  private val activityResultsManager = ActivityResultsManager(this)
  internal val appContextActivityResultCaller = DefaultAppContextActivityResultCaller(activityResultsManager)

  init {
    requireNotNull(reactContextHolder.get()) {
      "The app context should be created with valid react context."
    }.apply {
      addLifecycleEventListener(reactLifecycleDelegate)
      addActivityEventListener(reactLifecycleDelegate)

      // Registering modules has to happen at the very end of `AppContext` creation. Some modules need to access
      // `AppContext` during their initialisation, so we need to ensure all `AppContext`'s
      // properties are initialized first. Not having that would trigger NPE.
      hostingRuntimeContext.registry.register(ErrorManagerModule())
      hostingRuntimeContext.registry.register(NativeModulesProxyModule())
      hostingRuntimeContext.registry.register(modulesProvider)

      logger.info("✅ AppContext was initialized")
    }
  }

  fun onCreate() = trace("AppContext.onCreate") {
    hostingRuntimeContext.registry.postOnCreate()
  }

  /**
   * Initializes a JSI part of the module registry.
   * It will be a NOOP if the remote debugging was activated.
   */
  fun installJSIInterop() {
    hostingRuntimeContext.installJSIContext()
  }

  /**
   * Returns a legacy module implementing given interface.
   */
  inline fun <reified Module> legacyModule(): Module? {
    return try {
      legacyModuleRegistry.getModule(Module::class.java)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Provides access to app's constants from the legacy module registry.
   */
  val constants: ConstantsInterface?
    get() = legacyModule()

  /**
   * Provides access to the file system manager from the legacy module registry.
   */
  val filePermission: FilePermissionModuleInterface?
    get() = legacyModule()

  /**
   * Provides access to the scoped directories from the legacy module registry.
   */
  private val appDirectories: AppDirectoriesModuleInterface?
    get() = legacyModule()

  /**
   * A directory for storing user documents and other permanent files.
   */
  val persistentFilesDirectory: File
    get() = appDirectories?.persistentFilesDirectory
      ?: throw ModuleNotFoundException("expo.modules.interfaces.filesystem.AppDirectories")

  /**
   * A directory for storing temporary files that can be removed at any time by the device's operating system.
   */
  val cacheDirectory: File
    get() = appDirectories?.cacheDirectory
      ?: throw ModuleNotFoundException("expo.modules.interfaces.filesystem.AppDirectories")

  /**
   * Provides access to the permissions manager from the legacy module registry
   */
  val permissions: Permissions?
    get() = legacyModule()

  /**
   * Provides access to the image loader from the legacy module registry
   */
  val imageLoader: ImageLoaderInterface?
    get() = legacyModule()

  /**
   * Provides access to the camera view manager from the legacy module registry
   */
  val camera: CameraViewInterface?
    get() = legacyModule()

  /**
   * Provides access to the font manager from the legacy module registry
   */
  val font: FontManagerInterface?
    get() = legacyModule()

  /**
   * Provides access to the task manager from the legacy module registry
   */
  val taskManager: TaskManagerInterface?
    get() = legacyModule()

  /**
   * Provides access to the activity provider from the legacy module registry
   */
  val activityProvider: ActivityProvider?
    get() = legacyModule()

  /**
   * Provides access to the react application context
   */
  val reactContext: Context?
    get() = hostingRuntimeContext.reactContext

  /**
   * @return true if there is an non-null, alive react native instance
   */
  val hasActiveReactInstance: Boolean
    get() = hostingRuntimeContext.reactContext?.hasActiveReactInstance() ?: false

  /**
   * Provides access to the event emitter
   */
  fun eventEmitter(module: Module): EventEmitter? {
    val legacyEventEmitter = legacyModule<expo.modules.core.interfaces.services.EventEmitter>()
      ?: return null
    return KModuleEventEmitterWrapper(
      requireNotNull(hostingRuntimeContext.registry.getModuleHolder(module)) {
        "Cannot create an event emitter for the module that isn't present in the module registry."
      },
      legacyEventEmitter,
      hostingRuntimeContext.reactContextHolder
    )
  }

  internal val callbackInvoker: EventEmitter?
    get() {
      val legacyEventEmitter = legacyModule<expo.modules.core.interfaces.services.EventEmitter>()
        ?: return null
      return KEventEmitterWrapper(legacyEventEmitter, hostingRuntimeContext.reactContextHolder)
    }

  val errorManager: ErrorManagerModule?
    get() = hostingRuntimeContext.registry.getModule()

  internal fun onDestroy() = trace("AppContext.onDestroy") {
    hostingRuntimeContext.reactContext?.removeLifecycleEventListener(reactLifecycleDelegate)
    hostingRuntimeContext.registry.post(EventName.MODULE_DESTROY)
    hostingRuntimeContext.registry.cleanUp()
    modulesQueue.cancel(ContextDestroyedException())
    mainQueue.cancel(ContextDestroyedException())
    backgroundCoroutineScope.cancel(ContextDestroyedException())
    hostingRuntimeContext.deallocate()
    logger.info("✅ AppContext was destroyed")
  }

  internal fun onHostResume() {
    // If the current activity is null, it means that the current React context was destroyed.
    // We can just return here.
    val activity = currentActivity ?: return
    check(activity is AppCompatActivity) {
      "Current Activity is of incorrect class, expected AppCompatActivity, received ${currentActivity?.localClassName}"
    }

    // We need to re-register activity contracts when reusing AppContext with new Activity after host destruction.
    if (hostWasDestroyed) {
      hostWasDestroyed = false
      hostingRuntimeContext.registry.registerActivityContracts()
    }

    activityResultsManager.onHostResume(activity)
    hostingRuntimeContext.registry.post(EventName.ACTIVITY_ENTERS_FOREGROUND)
  }

  internal fun onHostPause() {
    hostingRuntimeContext.registry.post(EventName.ACTIVITY_ENTERS_BACKGROUND)
  }

  internal fun onUserLeaveHint() {
    hostingRuntimeContext.registry.post(EventName.ON_USER_LEAVES_ACTIVITY)
  }

  internal fun onHostDestroy() {
    currentActivity?.let {
      check(it is AppCompatActivity) {
        "Current Activity is of incorrect class, expected AppCompatActivity, received ${currentActivity?.localClassName}"
      }

      activityResultsManager.onHostDestroy(it)
    }
    hostingRuntimeContext.registry.post(EventName.ACTIVITY_DESTROYS)
    // The host (Activity) was destroyed, but it doesn't mean that modules will be destroyed too.
    // So we save that information, and we will re-register activity contracts when the host will be resumed with new Activity.
    hostWasDestroyed = true
  }

  internal fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
    activityResultsManager.onActivityResult(requestCode, resultCode, data)
    hostingRuntimeContext.registry.post(
      EventName.ON_ACTIVITY_RESULT,
      activity,
      OnActivityResultPayload(
        requestCode,
        resultCode,
        data
      )
    )
  }

  internal fun onNewIntent(intent: Intent?) {
    hostingRuntimeContext.registry.post(
      EventName.ON_NEW_INTENT,
      intent
    )
  }

  @Suppress("UNCHECKED_CAST")
  @UiThread
  fun <T : View> findView(viewTag: Int): T? {
    val reactContext = hostingRuntimeContext.reactContext ?: return null
    return UIManagerHelper.getUIManagerForReactTag(reactContext, viewTag)?.resolveView(viewTag) as? T
  }

  internal fun dispatchOnMainUsingUIManager(block: () -> Unit) {
    val reactContext = hostingRuntimeContext.reactContext ?: throw Exceptions.ReactContextLost()
    val uiManager = UIManagerHelper.getUIManagerForReactTag(
      reactContext,
      UIManagerType.DEFAULT
    ) as UIManagerModule

    uiManager.addUIBlock {
      block()
    }
  }

  internal fun assertMainThread() {
    Utils.assertMainThread()
  }

  /**
   * Runs a code block on the JavaScript thread.
   */
  fun executeOnJavaScriptThread(runnable: Runnable) {
    hostingRuntimeContext.reactContext?.runOnJSQueueThread(runnable)
  }

// region CurrentActivityProvider

  override val currentActivity: Activity?
    get() {
      return activityProvider?.currentActivity
        ?: (reactContext as? ReactApplicationContext)?.currentActivity
    }

  val throwingActivity: Activity
    get() {
      val current = activityProvider?.currentActivity
        ?: (reactContext as? ReactApplicationContext)?.currentActivity
      return current ?: throw Exceptions.MissingActivity()
    }

// endregion
}
