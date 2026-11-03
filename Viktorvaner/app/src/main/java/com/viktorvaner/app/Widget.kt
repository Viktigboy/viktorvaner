package com.viktorvaner.app

import android.content.Context
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Widget look. Change GLASS to adjust transparency: 0x00 = invisible, 0xFF = solid.
private val GLASS = Color(0x660A1020)
private val ACCENT = Color(0xFF3D7BFF)
private val SOFT_WHITE = Color(0x26FFFFFF)

class HabitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store.get(context)
        provideContent {
            val today = LocalDate.now()
            val list = store.habits.filter { !it.archived }
            val doneCount = list.count { store.isDone(it, today) }
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
                            "Today",
                            style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        )
                        Text(
                            today.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                            style = TextStyle(color = ColorProvider(Color(0xB3FFFFFF)), fontSize = 11.sp)
                        )
                    }
                    Box(
                        GlanceModifier.background(ColorProvider(Color(0x4D3D7BFF))).cornerRadius(10.dp)
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "$doneCount/${list.size}",
                            style = TextStyle(color = ColorProvider(Color(0xFFA9C6FF)), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        )
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                if (list.isEmpty()) {
                    Text(
                        "Open Viktorvaner to add habits",
                        style = TextStyle(color = ColorProvider(Color(0xB3FFFFFF)), fontSize = 13.sp)
                    )
                } else {
                    LazyColumn {
                        items(list, itemId = { it.id.hashCode().toLong() }) { h ->
                            val d = store.isDone(h, today)
                            Row(
                                GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clickable(actionRunCallback<ToggleAction>(actionParametersOf(ToggleAction.IdKey to h.id))),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    GlanceModifier.size(32.dp).background(ColorProvider(SOFT_WHITE)).cornerRadius(11.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text(h.icon, style = TextStyle(fontSize = 16.sp)) }
                                Spacer(GlanceModifier.width(10.dp))
                                Text(
                                    h.title,
                                    modifier = GlanceModifier.defaultWeight(),
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = ColorProvider(if (d) Color(0x99FFFFFF) else Color.White),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                                Box(
                                    GlanceModifier.size(24.dp)
                                        .background(ColorProvider(if (d) ACCENT else SOFT_WHITE))
                                        .cornerRadius(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (d) Text(
                                        "✓",
                                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[IdKey] ?: return
        Store.get(context).toggleId(id, LocalDate.now())
        HabitWidget().updateAll(context)
    }
    companion object { val IdKey = ActionParameters.Key<String>("habit_id") }
}

class HabitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitWidget()
}
