package com.johnsrud.lister

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// Widget look. Change GLASS to adjust transparency: 0x00 = invisible, 0xFF = solid.
private val GLASS = Color(0x660A1020)
private val ACCENT = Color(0xFF3D7BFF)
private val SOFT_WHITE = Color(0x26FFFFFF)

private fun handleItems() = FirebaseFirestore.getInstance()
    .collection("families").document(Repo.SPACE).collection("items")

/** Reads Handleliste straight from Firestore, so the widget works even when the app is closed. */
private suspend fun fetchHandle(): List<Item> {
    val q = handleItems().whereEqualTo("listId", Repo.HANDLE_ID)
    var snap: QuerySnapshot? = try {
        withTimeout(6000) { q.get().await() }
    } catch (e: Exception) { null }
    if (snap == null) {
        snap = try { q.get(Source.CACHE).await() } catch (e: Exception) { null }
    }
    if (snap == null) return emptyList()
    return snap.documents.map { d ->
        Item(d.id, Repo.HANDLE_ID, d.getString("name") ?: "", d.getBoolean("done") ?: false, d.getLong("created") ?: 0L)
    }
}

class HandleWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val fetched = fetchHandle()
        val todo = fetched.filter { !it.done }.sortedBy { it.created }
        val done = fetched.filter { it.done }.sortedBy { it.created }
        val all = todo + done
        val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        provideContent {
            Column(
                GlanceModifier.fillMaxSize()
                    .background(ColorProvider(GLASS))
                    .cornerRadius(26.dp)
                    .padding(14.dp)
            ) {
                Row(
                    GlanceModifier.fillMaxWidth().clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(GlanceModifier.defaultWeight()) {
                        Text(
                            "Handleliste",
                            style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        )
                        Text(
                            "Oppdatert $stamp",
                            style = TextStyle(color = ColorProvider(Color(0xB3FFFFFF)), fontSize = 11.sp)
                        )
                    }
                    Box(
                        GlanceModifier.background(ColorProvider(Color(0x4D3D7BFF))).cornerRadius(10.dp)
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${todo.size} igjen",
                            style = TextStyle(color = ColorProvider(Color(0xFFA9C6FF)), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        )
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                if (all.isEmpty()) {
                    Text(
                        "Handlelisten er tom",
                        style = TextStyle(color = ColorProvider(Color(0xB3FFFFFF)), fontSize = 13.sp)
                    )
                } else {
                    LazyColumn {
                        items(all, itemId = { x -> x.id.hashCode().toLong() }) { x ->
                            Row(
                                GlanceModifier.fillMaxWidth().padding(vertical = 5.dp)
                                    .clickable(
                                        actionRunCallback<ToggleHandleAction>(
                                            actionParametersOf(
                                                ToggleHandleAction.IdKey to x.id,
                                                ToggleHandleAction.DoneKey to x.done
                                            )
                                        )
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    GlanceModifier.size(24.dp)
                                        .background(ColorProvider(if (x.done) ACCENT else SOFT_WHITE))
                                        .cornerRadius(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (x.done) Text(
                                        "✓",
                                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                                Spacer(GlanceModifier.width(10.dp))
                                Text(
                                    x.name,
                                    modifier = GlanceModifier.defaultWeight(),
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = ColorProvider(if (x.done) Color(0x99FFFFFF) else Color.White),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = if (x.done) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

class ToggleHandleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[IdKey] ?: return
        val wasDone = parameters[DoneKey] ?: false
        try {
            withTimeoutOrNull(5000) { handleItems().document(id).update("done", !wasDone).await() }
        } catch (e: Exception) { }
        HandleWidget().updateAll(context)
    }

    companion object {
        val IdKey = ActionParameters.Key<String>("item_id")
        val DoneKey = ActionParameters.Key<Boolean>("item_done")
    }
}

class HandleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HandleWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleWidgetRefresh(context)
    }
}

/** Refreshes the widget every 15 minutes (the shortest interval Android allows) when online. */
class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try { HandleWidget().updateAll(applicationContext) } catch (e: Exception) { }
        return Result.success()
    }
}

fun scheduleWidgetRefresh(ctx: Context) {
    val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        "handle_widget_refresh", ExistingPeriodicWorkPolicy.KEEP, request
    )
}
