package com.feedflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.feedflow.app.ui.FeedFlowApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e("UNCAUGHT EXCEPTION on ${thread.name}: ${throwable.message}", throwable)
            // Write stack trace to log
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            AppLogger.e("Stack trace:\n$sw")
        }
        enableEdgeToEdge()
        setContent { FeedFlowApp() }
    }
}
