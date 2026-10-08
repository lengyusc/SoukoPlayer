package com.souko.soukoplayer

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle

class MyApplication : Application() {

    private var activityReferences = 0
    private var isActivityChangingConfigurations = false
    private var audioService: AudioService? = null
    private var isBound = false

    // 标记是否用户主动退出
    private var isUserExiting = false

    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                activityReferences++
                isActivityChangingConfigurations = activity.isChangingConfigurations
                isUserExiting = false // 重置退出标记
                println("DEBUG: Activity started, references: $activityReferences")
            }

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                activityReferences--
                isActivityChangingConfigurations = activity.isChangingConfigurations
                println("DEBUG: Activity stopped, references: $activityReferences, changingConfig: $isActivityChangingConfigurations")

                // 应用退到后台，但不清理服务（保持后台播放）
                if (activityReferences == 0 && !isActivityChangingConfigurations) {
                    println("DEBUG: App went to background, keeping service for playback")
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                // 当 MainActivity 被销毁时，如果是用户退出则清理
                if (activity is MainActivity && activity.isFinishing && !isActivityChangingConfigurations) {
                    println("DEBUG: MainActivity destroyed, user may be exiting")
                    // 这里可以添加退出确认逻辑，或者依赖 AndroidManifest 的 stopWithTask
                }
            }
        })
    }

    // 用户主动退出时调用（如设置中的退出按钮）
    fun stopAudioServiceAndCleanUp() {
        println("DEBUG: User explicitly requested to stop service")
        isUserExiting = true
        cleanUpOnAppExit()
    }

    // 真正的清理逻辑
    private fun cleanUpOnAppExit() {
        println("DEBUG: Cleaning up services on app exit")

        // 使用 AudioService 的清理方法
        if (isBound) {
            audioService?.stopServiceAndCleanup()
        } else {
            // 发送清理指令给服务
            val intent = Intent(this, AudioService::class.java).apply {
                action = "ACTION_STOP_SERVICE"
            }
            startService(intent)
        }
    }
}