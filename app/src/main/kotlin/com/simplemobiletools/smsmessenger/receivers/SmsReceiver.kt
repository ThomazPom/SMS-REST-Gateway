package com.simplemobiletools.smsmessenger.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.simplemobiletools.commons.extensions.baseConfig
import com.simplemobiletools.commons.extensions.getMyContactsCursor
import com.simplemobiletools.commons.extensions.isNumberBlocked
import com.simplemobiletools.commons.helpers.SimpleContactsHelper
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.models.PhoneNumber
import com.simplemobiletools.commons.models.SimpleContact
import com.simplemobiletools.smsmessenger.extensions.*
import com.simplemobiletools.smsmessenger.helpers.refreshMessages
import com.simplemobiletools.smsmessenger.models.Message
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections

class SmsReceiver : BroadcastReceiver() {


    private fun sendToGateway(
        context: Context,
        address: String,
        subject: String,
        body: String,
        date: Long,
        read: Int,
        threadId: Long,
        type: Int,
        subscriptionId: Int,
        status: Int
    ) {
        Log.d("SMS TO GATEWAY", body)
        sendGetRequest(context.config.gatewayURL,context.config.gatewayPassword, mapOf(
            "source" to address,
            "subject" to subject,
            "body" to body

        ))
    }

    private fun sendPostRequest(mURL: URL,authorization:String,data:Map<String,String>) {
        with(mURL.openConnection() as HttpURLConnection) {
            // optional default is GET
            requestMethod = "POST"
            addRequestProperty("Authorization",authorization)
            val postData = StringBuilder()
            for ((key, value) in data) {
                if (postData.isNotEmpty()) {
                    postData.append('&')
                }
                postData.append(URLEncoder.encode(key, "UTF-8"))
                postData.append('=')
                postData.append(URLEncoder.encode(value, "UTF-8"))
            }

            val wr = OutputStreamWriter(getOutputStream());
            wr.write(postData.toString());
            wr.flush();

            println("URL : $url")
            println("Response Code : $responseCode")

            BufferedReader(InputStreamReader(inputStream)).use {
                val response = StringBuffer()

                var inputLine = it.readLine()
                while (inputLine != null) {
                    response.append(inputLine)
                    inputLine = it.readLine()
                }
                println("Response : $response")
            }
        }
    }

    private fun sendGetRequest(baseUrl:String?,authorization:String?,data:Map<String,String>) {

        val getData = StringBuilder()
        for ((key, value) in data) {
            if (getData.isNotEmpty()) {
                getData.append('&')
            }
            getData.append(URLEncoder.encode(key, "UTF-8"))
            getData.append('=')
            getData.append(URLEncoder.encode(value, "UTF-8"))
        }

        val queryString = buildString {
            data.forEach { (key, value) ->
                if (length > 0) {
                    append("&")
                }
                append(URLEncoder.encode(key, "UTF-8"))
                append("=")
                append(URLEncoder.encode(value, "UTF-8"))
            }
        }

        val urlString = "$baseUrl?$queryString"
        val mURL = URL(urlString)


        with(mURL.openConnection() as HttpURLConnection) {
            // optional default is GET
            requestMethod = "GET"
            addRequestProperty("Authorization",authorization)

            println("URL : $url")
            println("Response Code : $responseCode")

            BufferedReader(InputStreamReader(inputStream)).use {
                val response = StringBuffer()

                var inputLine = it.readLine()
                while (inputLine != null) {
                    response.append(inputLine)
                    inputLine = it.readLine()
                }
                println("Response : $response")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        var address = ""
        var body = ""
        var subject = ""
        var date = 0L
        var threadId = 0L
        var status = Telephony.Sms.STATUS_NONE
        val type = Telephony.Sms.MESSAGE_TYPE_INBOX
        val read = 0
        val subscriptionId = intent.getIntExtra("subscription", -1)

        val privateCursor = context.getMyContactsCursor(false, true)
        ensureBackgroundThread {
            messages.forEach {
                address = it.originatingAddress ?: ""
                subject = it.pseudoSubject
                status = it.status
                body += it.messageBody
                date = System.currentTimeMillis()
                threadId = context.getThreadId(address)
            }

            if (context.baseConfig.blockUnknownNumbers) {
                val simpleContactsHelper = SimpleContactsHelper(context)
                simpleContactsHelper.exists(address, privateCursor) { exists ->
                    if (exists) {
                        handleMessage(context, address, subject, body, date, read, threadId, type, subscriptionId, status)
                    }
                }
            } else {
                handleMessage(context, address, subject, body, date, read, threadId, type, subscriptionId, status)
            }
        }
    }

    private fun handleMessage(
        context: Context,
        address: String,
        subject: String,
        unMutableBody: String,
        date: Long,
        read: Int,
        threadId: Long,
        type: Int,
        subscriptionId: Int,
        status: Int
    ) {
        var body=unMutableBody
        if (isMessageFilteredOut(context, body)) {
            return
        }
        if (context.config.sendSmsToGateway) {
            try {
                sendToGateway(context, address, subject, body, date, read, threadId, type, subscriptionId, status)
            }
            catch (e: Exception)
            {
                Log.d("SMS TO GATEWAY",e.toString())
                body =body+ "\n\nSMS TO GATEWAY ERROR:\n"
                body =body+e.toString()
            }
        }
        if (context.config.disableSMSLogging) {
            return;
        }
        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)
        Handler(Looper.getMainLooper()).post {
            if (!context.isNumberBlocked(address)) {
                val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                ensureBackgroundThread {
                    val newMessageId = context.insertNewSMS(address, subject, body, date, read, threadId, type, subscriptionId)

                    val conversation = context.getConversations(threadId).firstOrNull() ?: return@ensureBackgroundThread
                    try {
                        context.insertOrUpdateConversation(conversation)
                    } catch (ignored: Exception) {
                    }

                    try {
                        context.updateUnreadCountBadge(context.conversationsDB.getUnreadConversations())
                    } catch (ignored: Exception) {
                    }

                    val senderName = context.getNameFromAddress(address, privateCursor)
                    val phoneNumber = PhoneNumber(address, 0, "", address)
                    val participant = SimpleContact(0, 0, senderName, photoUri, arrayListOf(phoneNumber), ArrayList(), ArrayList())
                    val participants = arrayListOf(participant)
                    val messageDate = (date / 1000).toInt()

                    val message =
                        Message(
                            newMessageId,
                            body,
                            type,
                            status,
                            participants,
                            messageDate,
                            false,
                            threadId,
                            false,
                            null,
                            address,
                            senderName,
                            photoUri,
                            subscriptionId
                        )
                    context.messagesDB.insertOrUpdate(message)
                    if (context.config.isArchiveAvailable) {
                        context.updateConversationArchivedStatus(threadId, false)
                    }
                    refreshMessages()
                    context.showReceivedMessageNotification(newMessageId, address, body, threadId, bitmap)
                }
            }
        }
    }

    private fun isMessageFilteredOut(context: Context, body: String): Boolean {
        for (blockedKeyword in context.config.blockedKeywords) {
            if (body.contains(blockedKeyword, ignoreCase = true)) {
                return true
            }
        }

        return false
    }
}
