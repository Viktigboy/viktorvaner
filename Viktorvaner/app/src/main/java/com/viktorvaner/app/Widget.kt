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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.LocalDate

class HabitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store.get(context)
        provideContent {
            val today = LocalDate.now()
            val list = store.habits.filter { !it.archived }
            val doneCount = list.count { store.isDone(it.id, today) }
            Column(
                GlanceModifier.fillMaxSize()
                    .background(ColorProvider(Color(0xFF0B2A6F)))
                    .cornerRadius(22.dp)
                    .padding(12.dp)
            ) {
                Text(
                    "Viktorvaner  $doneCount/${list.size}",
                    style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                )
                Spacer(GlanceModifier.height(6.dp))
                if (list.isEmpty()) {
                    Text("Open the app to add habits", style = TextStyle(color = ColorProvider(Color(0xFFB8D0FF)), fontSize = 13.sp))
                } else {
                    LazyColumn {
                        items(list, itemId = { it.id.hashCode().toLong() }) { h ->
                            val d = store.isDone(h.id, today)
                            Row(
                                GlanceModifier.fillMaxWidth().padding(vertical = 5.dp)
                                    .clickable(actionRunCallback<ToggleAction>(actionParametersOf(ToggleAction.IdKey to h.id))),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(h.icon, style = TextStyle(fontSize = 18.sp))
                                Spacer(GlanceModifier.width(8.dp))
                                Text(
                                    h.title,
                                    modifier = GlanceModifier.defaultWeight(),
                                    style = TextStyle(
                                        color = ColorProvider(if (d) Color(0xFF7FB0FF) else Color.White),
                                        fontSize = 15.sp,
                                        textDecoration = if (d) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                )
                                Text(
                                    if (d) "✓" else "○",
                                    style = TextStyle(
                                        color = ColorProvider(if (d) Color(0xFF5CE1A0) else Color(0xFFB8D0FF)),
                                        fontSize = 20.sp, fontWeight = FontWeight.Bold
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

class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[IdKey] ?: return
        Store.get(context).toggle(id, LocalDate.now())
        HabitWidget().updateAll(context)
    }
    companion object { val IdKey = ActionParameters.Key<String>("habit_id") }
}

class HabitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitWidget()
}
