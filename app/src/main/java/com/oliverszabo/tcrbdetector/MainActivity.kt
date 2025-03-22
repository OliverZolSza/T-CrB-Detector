package com.oliverszabo.tcrbdetector

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

//This class handles the background process for notifying
class BackgroundWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    //Notifications
    private fun createNotificationChannel(context: Context) {
        val channelId = "t_crb_id"
        val channelName = "Background Notifier For T CrB"
        val channelDescription = "Notifies on the explosion of T CrB"
        val importance = NotificationManager.IMPORTANCE_HIGH

        val channel = NotificationChannel(channelId, channelName, importance).apply {
            description = channelDescription
        }

        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    private fun sendNotification(context: Context, title: String = "placeholder", text: String = "placeholder") {
        val channelId = "t_crb_id"
        val notificationId = 1

        // Create an intent that will be fired when the notification is clicked
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Build the notification
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Dismiss when pressed
            .build()

        // Send the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    override fun doWork(): Result {
        createNotificationChannel(applicationContext)

        coroutineScope.launch {
            val mainActivity = MainActivity()
            val valueDeferred = mainActivity.fetchWebsiteContent("https://theskylive.com/sky/stars/hr-5958-star")
            val value = valueDeferred.await() ?: ""
            val regex = """([+-]?\d+(\.\d+)?)""".toRegex()
            val magnitude = regex.find(value)?.value?.toFloat()

            if (magnitude != null) {
                if (magnitude < 4.0)
                    sendNotification(applicationContext, "T CrB Has Exploded! Value:", value ?: "NULL")
            }
        }
        return Result.success()
    }
}




class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    private val permissionRequestCode = 100
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkBatteryOptimization()
        checkPermissionsAndScheduleWorker()

        coroutineScope.launch {
            val valueDeferred = fetchWebsiteContent("https://theskylive.com")
            val value = valueDeferred.await()

            val magnitudeTextView = findViewById<TextView>(R.id.magnitudeTextView)

            // Update the TextView with the fetched value
            magnitudeTextView.text = value?: "No magnitude found"
        }
    }

    //Battery Permission
    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimization() {
        val intent = Intent()
        intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    }

    //Handle Website and Magnitude
    fun fetchWebsiteContent(url: String): Deferred<String?> {
        return CoroutineScope(Dispatchers.IO).async {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val htmlContent = response.body?.string()
                if (htmlContent != null) {
                    val magnitude = extractMagnitude(htmlContent)
                    // Return the extracted magnitude
                    return@async magnitude
                }
            } else {
                println("Failed to fetch content: ${response.message}")
            }
            // Return null if the request was not successful or content was not found
            return@async null
        }
    }
    private fun extractMagnitude(htmlContent: String): String? {
        val document: Document = Jsoup.parse(htmlContent)
        return document.select(".hilights li:has(number) number").first()?.text()?.trim()
    }

    //Handle Background Process
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkPermissionsAndScheduleWorker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), permissionRequestCode)
        } else {
            scheduleWorker()
        }
    }
    private fun scheduleWorker() {
        // Create a PeriodicWorkRequest for the BackgroundWorker
        val workRequest = PeriodicWorkRequestBuilder<BackgroundWorker>(2, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "Unique Background Worker for T CrB",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    /*
    // Create an unsafe OkHttpClient that ignores SSL certificate validation
    private fun createUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true } // Accept all hostnames
            .build()
    }

    // Create a global OkHttpClient instance
    private val client = createUnsafeOkHttpClient()

    private fun fetchWebsiteContent(url: String): Deferred<String?> {
        return CoroutineScope(Dispatchers.IO).async {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val htmlContent = response.body?.string()
                if (htmlContent != null) {
                    val magnitude = extractMagnitude(htmlContent)
                    // Return the extracted magnitude
                    return@async magnitude
                }
            } else {
                println("Failed to fetch content: ${response.message}")
            }
            // Return null if the request was not successful or content was not found
            return@async null
        }
    }

    private fun extractMagnitude(htmlContent: String): String? {
        val document: Document = Jsoup.parse(htmlContent)

        // Select the <ar> tag within the <div class="keyinfobox">
        val magnitudeElement = document.select("div.keyinfobox ar").first()

        // Return the text content of the <ar> tag, or null if not found
        return magnitudeElement?.text()?.trim()
    }
    */
}