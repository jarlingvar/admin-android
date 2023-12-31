package com.jarlingwar.adminapp.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.jarlingwar.adminapp.MainActivity
import com.jarlingwar.adminapp.R
import com.jarlingwar.adminapp.data.network.FcmApi
import com.jarlingwar.adminapp.domain.models.FcmMessage
import com.jarlingwar.adminapp.domain.models.ListingModel
import com.jarlingwar.adminapp.domain.models.NotificationModel
import com.jarlingwar.adminapp.domain.models.ReviewModel
import com.jarlingwar.adminapp.domain.models.UserModel
import com.jarlingwar.adminapp.domain.repositories.remote.IChatRepository
import com.jarlingwar.adminapp.domain.repositories.remote.INewListingsRepository
import com.jarlingwar.adminapp.domain.repositories.remote.IReviewRepository
import com.jarlingwar.adminapp.utils.ListingFields
import com.jarlingwar.adminapp.utils.ReportHandler
import com.jarlingwar.adminapp.utils.ReviewFields
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

enum class IntentType {
    NEW_LISTING,
    NEW_REVIEW
}

@AndroidEntryPoint
class MonitoringService : Service() {

    @Inject
    lateinit var chatRepo: IChatRepository

    @Inject
    lateinit var reviewsRepo: IReviewRepository

    @Inject
    lateinit var fcmApi: FcmApi

    @Inject
    lateinit var newListingsRepo: INewListingsRepository

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processedItems = arrayListOf<String>()
    private val sentNotifications = arrayListOf<NotificationModel>()
    private var listingNotificationsNum = 0
    private var reviewNotificationsNum = 0

    companion object {
        const val INTENT_TYPE = "intentType"
        private const val SUMMARY_ID = 123456
        private const val CHANNEL_ID = "firebase monitoring service"
        private const val MESSAGE_CHANNEL_ID = "101"
        private const val MESSAGE_CHANNEL_NAME = "monitoringAlerts"
        private const val FOREGROUND_MSG_CONTENT = "firebase monitoring service is active"
        private const val FOREGROUND_MSG_TITLE = "Monitoring service"
        private const val PENDING_GROUP = "pendings"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setupForeground()
        setupObservers()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun setupForeground() {
        val channel =
            NotificationChannel(CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification.Builder = Notification.Builder(this, CHANNEL_ID)
            .setContentText(FOREGROUND_MSG_CONTENT)
            .setContentTitle(FOREGROUND_MSG_TITLE)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setSmallIcon(R.drawable.ic_computer)
        startForeground(1001, notification.build())
        createChannel()
    }

    private fun setupObservers() {
        //observe unapproved listings
        coroutineScope.launch(Dispatchers.IO) {
            newListingsRepo.getNewListingsAsFlow()
                .catch { ReportHandler.reportError(it) }
                .collect { listings ->
                    listings.forEach { listing ->
                        sendNotification(listing)
                    }
                }
        }
        //observe unapproved reviews
        coroutineScope.launch(Dispatchers.IO) {
            reviewsRepo.getReviewsAsFlow()
                .catch { ReportHandler.reportError(it) }
                .collectLatest { reviews ->
                    reviews.forEach { review ->
                        sendNotification(review)
                    }
                }
        }
        //notify users about new messages
        coroutineScope.launch {
            chatRepo.getNotificationQueueAsFlow()
                .collectLatest { notifications ->
                    notifications.distinct().forEach { notification ->
                        sendNotification(notification)
                        delay(20)
                    }
                }
        }
    }

    private fun sendNotification(reviewModel: ReviewModel) {
        if (processedItems.contains(reviewModel.id)) return
        else processedItems.add(reviewModel.id)
        val title = "Review approval required"
        val body =
            "${reviewModel.reviewerName} ${reviewModel.rating} ${reviewModel.body}"
        val imgUrl = reviewModel.reviewerImageUrl
        ImageRequest.Builder(this)
            .data(imgUrl)
        val data = getIntentData(reviewModel)
        val pi = getIntent(applicationContext, data)

        val approveIntent: PendingIntent =
            getReceiverIntent(reviewModel.id, ActionReceiver.ACTION_APPROVE_REVIEW, 1)
        val rejectIntent: PendingIntent =
            getReceiverIntent(reviewModel.id, ActionReceiver.ACTION_REJECT_REVIEW, 1)

        val approveAction =
            Notification.Action.Builder(R.drawable.ic_thumb_up, "Approve", approveIntent)
                .build()

        val rejectAction =
            Notification.Action.Builder(R.drawable.ic_thumb_down, "Reject", rejectIntent)
                .build()

        val builder = Notification.Builder(applicationContext, MESSAGE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_new_user)
            .setActions(approveAction, rejectAction)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setGroup(PENDING_GROUP)
            .setAutoCancel(true)

        val request = ImageRequest.Builder(this)
            .data(imgUrl)
            .target(onSuccess = {
                val bitmap = (it as BitmapDrawable).bitmap
                val notification = builder
                    .setLargeIcon(bitmap)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .build()
                showNotification(notification)
            }, onError = { showNotification(builder.build()) })
            .build()
        ImageLoader(this).enqueue(request)
        reviewNotificationsNum++
        groupMessages()
    }


    private fun sendNotification(listingModel: ListingModel) {
        if (processedItems.contains(listingModel.listingId)) return
        else processedItems.add(listingModel.listingId)
        val title = "Listing approval required"
        val body =
            "${listingModel.title} price: ${listingModel.price} , location:${listingModel.location?.locationName}"
        val imgUrl = listingModel.remoteImgUrlList.firstOrNull() ?: ""
        ImageRequest.Builder(this)
            .data(imgUrl)
        val data = getIntentData(listingModel)
        val pi = getIntent(applicationContext, data)
        val receiverIntent: PendingIntent =
            getReceiverIntent(listingModel.listingId, ActionReceiver.ACTION_APPROVE_LISTING, 0)

        val approveAction =
            Notification.Action.Builder(R.drawable.ic_thumb_up, "Approve", receiverIntent)
                .build()

        val builder = Notification.Builder(applicationContext, MESSAGE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_published_listings)
            .setActions(approveAction)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setGroup(PENDING_GROUP)
            .setAutoCancel(true)
        val request = ImageRequest.Builder(this)
            .data(imgUrl)
            .target(onSuccess = {
                val bitmap = (it as BitmapDrawable).bitmap
                val largeIcon: Icon? = null
                val notification = builder
                    .setLargeIcon(bitmap)
                    .setStyle(Notification
                        .BigPictureStyle()
                        .setSummaryText(listingModel.description)
                        .bigPicture(bitmap)
                        .bigLargeIcon(largeIcon)
                    )
                    .build()
                showNotification(notification)
            }, onError = { showNotification(builder.build()) })
            .build()
        ImageLoader(this).enqueue(request)
        listingNotificationsNum++
        groupMessages()
    }

    private fun groupMessages() {
        val title = "Unprocessed items: ${reviewNotificationsNum + listingNotificationsNum}"
        val body = "Listings: $listingNotificationsNum , Reviews: $reviewNotificationsNum"
        val notification = Notification.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_app_launcher)
            .setStyle(
                Notification.InboxStyle()
                    .setBigContentTitle(title)
                    .setSummaryText(body)
            )
            .setGroup(PENDING_GROUP)
            .setGroupSummary(true)
            .build()
        showNotification(notification, SUMMARY_ID)
    }

    private fun getReceiverIntent(
        id: String,
        action: String,
        intentCode: Int
    ): PendingIntent {
        val intent = Intent(this, ActionReceiver::class.java)
        intent.putExtra(ActionReceiver.ITEM_ID, id)
        intent.action = action
        return PendingIntent.getBroadcast(this, intentCode, intent, PendingIntent.FLAG_MUTABLE)
    }

    private fun showNotification(notification: Notification, notificationId: Int = Random.nextInt()) {
        val hasNotificationPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        if (hasNotificationPerm) {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        }
    }

    private fun sendNotification(notification: NotificationModel) {
        try {
            if (sentNotifications.contains(notification)) return
            coroutineScope.launch {
                val sender = UserModel(
                    userId = notification.senderId,
                    displayName = notification.senderName,
                    profileImageUrl = notification.senderImgUrl
                )
                val receiver = UserModel(
                    fcmToken = notification.receiverFcmToken,
                    userId = notification.receiverId
                )
                val fcmMessage = FcmMessage(
                    receiver = receiver,
                    sender = sender,
                    title = sender.displayName,
                    messageBody = notification.messageBody
                )
                val response = fcmApi.sendMessage(fcmMessage)
                sentNotifications.add(notification)
                if (response.isSuccessful) {
                    chatRepo.delete(notification)
                } else {
                    sentNotifications.remove(notification)
                }
            }
        } catch (e: Exception) {
            ReportHandler.reportError(e)
        }
    }

    private fun getIntentData(listingModel: ListingModel): Map<String, String> {
        return mapOf(
            INTENT_TYPE to IntentType.NEW_LISTING.name,
            ListingFields.ID to listingModel.listingId
        )
    }

    private fun getIntentData(reviewModel: ReviewModel): Map<String, String> {
        return mapOf(
            INTENT_TYPE to IntentType.NEW_REVIEW.name,
            ReviewFields.ID to reviewModel.id
        )
    }

    private fun getIntent(context: Context, data: Map<String, String>): PendingIntent {
        val resultIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            data.forEach { (key, value) -> putExtra(key, value) }
        }
        val requestCode = Random.nextInt()
        return PendingIntent.getActivity(
            context,
            requestCode,
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                MESSAGE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
        channel.enableVibration(true)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}