package io.agora.agora_rtc_engine

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.NonNull
import io.agora.rtc.Constants
import io.agora.rtc.IRtcEngineEventHandler
import io.agora.rtc.RtcEngine
import io.agora.rtc.base.RtcEngineManager
import io.agora.rtc.base.screenshare.capture.*
import io.agora.rtc.video.AgoraVideoFrame
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugin.platform.PlatformViewRegistry
import java.util.*


/** AgoraRtcEnginePlugin */
class AgoraRtcEnginePlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler  {
  private var registrar: Registrar? = null
  private var binding: FlutterPlugin.FlutterPluginBinding? = null
  private lateinit var applicationContext: Context

  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var methodChannel: MethodChannel
  private lateinit var eventChannel: EventChannel

  private var eventSink: EventChannel.EventSink? = null
  private val manager = RtcEngineManager { methodName, data -> emit(methodName, data) }
  private val handler = Handler(Looper.getMainLooper())
  private val rtcChannelPlugin = AgoraRtcChannelPlugin(this)

  //Activity and Context
//  private lateinit var context: Context
  private lateinit var activity: Activity

  // This static function is optional and equivalent to onAttachedToEngine. It supports the old
  // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
  // plugin registration via this function while apps migrate to use the new Android APIs
  // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
  //
  // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
  // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
  // depending on the user's project. onAttachedToEngine or registerWith must both be defined
  // in the same class.
  companion object {
    @JvmStatic
    fun registerWith(registrar: Registrar) {
      AgoraRtcEnginePlugin().apply {
        this.registrar = registrar
        rtcChannelPlugin.initPlugin(registrar.messenger())
        initPlugin(registrar.context(), registrar.messenger(), registrar.platformViewRegistry())
      }
    }
  }

  private fun initPlugin(
    context: Context,
    binaryMessenger: BinaryMessenger,
    platformViewRegistry: PlatformViewRegistry
  ) {
    applicationContext = context.applicationContext
    methodChannel = MethodChannel(binaryMessenger, "agora_rtc_engine")
    methodChannel.setMethodCallHandler(this)
    eventChannel = EventChannel(binaryMessenger, "agora_rtc_engine/events")
    eventChannel.setStreamHandler(this)

    platformViewRegistry.registerViewFactory(
      "AgoraSurfaceView",
      AgoraSurfaceViewFactory(binaryMessenger, this, rtcChannelPlugin)
    )
    platformViewRegistry.registerViewFactory(
      "AgoraTextureView",
      AgoraTextureViewFactory(binaryMessenger, this, rtcChannelPlugin)
    )
  }

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    this.binding = binding
//    context = flutterPluginBinding.applicationContext
     activity = binding.applicationContext as Activity

    rtcChannelPlugin.onAttachedToEngine(binding)
    initPlugin(binding.applicationContext, binding.binaryMessenger, binding.platformViewRegistry)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    rtcChannelPlugin.onDetachedFromEngine(binding)
    methodChannel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
    manager.release()
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    eventSink = events
  }

  override fun onCancel(arguments: Any?) {
    eventSink = null
  }

  private fun emit(methodName: String, data: Map<String, Any?>?) {
    handler.post {
      val event: MutableMap<String, Any?> = mutableMapOf("methodName" to methodName)
      data?.let { event.putAll(it) }
      eventSink?.success(event)
    }
  }

  fun engine(): RtcEngine? {
    return manager.engine
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    val textureRegistry = registrar?.textures() ?: binding?.textureRegistry
    val messenger = registrar?.messenger() ?: binding?.binaryMessenger
    if (call.method == "createTextureRender") {
      val id = AgoraTextureViewFactory.createTextureRender(
        textureRegistry!!,
        messenger!!,
        this,
        rtcChannelPlugin
      )
      result.success(id)
      return
    } else if (call.method == "destroyTextureRender") {
      (call.arguments<Map<*, *>>()?.get("id") as? Number)?.let {
        AgoraTextureViewFactory.destroyTextureRender(it.toLong())
        result.success(null)
      }
      return
    }

    if (call.method == "getAssetAbsolutePath") {
      getAssetAbsolutePath(call, result)
      return
    }
    if (call.method == "screenShare") {
      val metrics = DisplayMetrics()
      activity.getWindowManager().getDefaultDisplay().getMetrics(metrics)
      var mScreenCapture: ScreenCapture? = null
      var mScreenGLRender: GLRender? = null
      var mRtcEngine: RtcEngine? = null


      if (mScreenGLRender == null) {
        mScreenGLRender = GLRender()
      }
      if (mScreenCapture == null) {
        mScreenCapture = ScreenCapture(applicationContext.getApplicationContext(), mScreenGLRender, metrics.densityDpi)
      }

      mScreenCapture.mImgTexSrcConnector.connect(object : SinkConnector<ImgTexFrame?>() {
        override fun onFormatChanged(obj: Any) {
          Log.d(
           "ScreenShare",
            "onFormatChanged " + obj.toString()
          )
        }

        override fun onFrameAvailable(frame: ImgTexFrame?) {
          Log.d(
            "ScreenShare",
            "onFrameAvailable " + frame.toString()
          )
          if (mRtcEngine == null) {
            return
          }
          val vf: AgoraVideoFrame = AgoraVideoFrame()
          vf.format = AgoraVideoFrame.FORMAT_TEXTURE_OES
          vf.timeStamp = frame!!.pts
          vf.stride = frame!!.mFormat.mWidth
          vf.height = frame!!.mFormat.mHeight
          vf.textureID = frame!!.mTextureId
          vf.syncMode = true
          vf.eglContext14 = mScreenGLRender.getEGLContext()
          vf.transform = frame!!.mTexMatrix
          mRtcEngine?.pushExternalVideoFrame(vf)
        }



      })

//
//      mScreenCapture.setOnScreenCaptureListener(object : ScreenCapture.OnScreenCaptureListener() {
//        override fun onStarted(){
//          Log.d(
//            "ScreenShare",
//            "Screen Record Started"
//          )
//        }
//      override fun onError(err:Int){
//          Log.d(
//            "ScreenShare",
//            "onError $err"
//          )
//          when (err) {
//            ScreenCapture.SCREEN_ERROR_SYSTEM_UNSUPPORTED -> {
//              Log.d(
//                "ScreenShare",
//                "Device Doesnt support Screen Share"
//              )
//            }
//            ScreenCapture.SCREEN_ERROR_PERMISSION_DENIED -> {
//              Log.d(
//                "ScreenShare",
//                "Permission Denied"
//              )
//
//
//            }
//          }
//        }
//      })

      val wm: WindowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      val screenWidth: Int = wm.getDefaultDisplay().getWidth()
      val screenHeight: Int = wm.getDefaultDisplay().getHeight()
      mScreenGLRender.init(screenWidth, screenHeight)

      mRtcEngine = RtcEngine.create(
       applicationContext,

        "",//TODO: Pass AppID
        object : IRtcEngineEventHandler() {
          override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            Log.d(
             "ScreenShare",
              "onJoinChannelSuccess $channel $elapsed"
            )
          }

          override fun onWarning(warn: Int) {
            Log.d(
             "ScreenShare",
              "onWarning $warn"
            )
          }

          override fun onError(err: Int) {
            Log.d(
        "ScreenShare",
              "onError $err"
            )
          }

          override fun onAudioRouteChanged(routing: Int) {
            Log.d(
              "ScreenShare",
              "onAudioRouteChanged $routing"
            )
          }
        })

      mRtcEngine.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
      mRtcEngine.enableVideo()
      if (mRtcEngine.isTextureEncodeSupported) {
        mRtcEngine.setExternalVideoSource(true, true, true)
      } else {
        throw RuntimeException("Can not work on device do not supporting texture" + mRtcEngine.isTextureEncodeSupported())
      }
      mRtcEngine.setVideoProfile(Constants.VIDEO_PROFILE_360P, true)

      mRtcEngine.setClientRole(Constants.CLIENT_ROLE_BROADCASTER, null)


    }
    manager.javaClass.declaredMethods.find { it.name == call.method }?.let { function ->
      function.let { method ->
        try {
          val parameters = mutableListOf<Any?>()
          call.arguments<Map<*, *>>()?.toMutableMap()?.let {
            if (call.method == "create") {
              it["context"] = applicationContext
            }
            parameters.add(it)
          }
          method.invoke(manager, *parameters.toTypedArray(), ResultCallback(result))
          return@onMethodCall
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }
    result.notImplemented()
  }

  private fun getAssetAbsolutePath(call: MethodCall, result: Result) {
    call.arguments<String>()?.let {
      val assetKey = registrar?.lookupKeyForAsset(it)
        ?: binding?.flutterAssets?.getAssetFilePathByName(it)
      try {
        applicationContext.assets.openFd(assetKey!!).close()
        result.success("/assets/$assetKey")
      } catch (e: Exception) {
        result.error(e.javaClass.simpleName, e.message, e.cause)
      }
      return@getAssetAbsolutePath
    }
    result.error(IllegalArgumentException::class.simpleName, null, null)
  }
}
