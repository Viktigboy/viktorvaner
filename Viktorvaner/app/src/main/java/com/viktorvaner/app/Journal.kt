package com.viktorvaner.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

val NB: Locale = Locale("nb", "NO")

fun cap(s: String): String = s.replaceFirstChar { ch -> ch.uppercase() }

fun dateTitle(d: LocalDate): String = cap(d.format(DateTimeFormatter.ofPattern("EEEE d. MMMM yyyy", NB)))

/** Keyboard capitalises the first letter of every sentence, like other apps. */
val CAPS = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

// ───────────────────────── Fingeravtrykk ─────────────────────────

fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** Asks for fingerprint (or the phone's screen lock as a fallback) and calls [onOk] when approved. */
fun authenticate(ctx: Context, title: String, onOk: () -> Unit) {
    val act = ctx.findFragmentActivity()
    if (act == null) {
        Toast.makeText(ctx, "Kunne ikke åpne fingeravtrykk-dialogen.", Toast.LENGTH_LONG).show()
        return
    }
    val allowed = if (Build.VERSION.SDK_INT >= 30) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
    if (BiometricManager.from(act).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        Toast.makeText(
            ctx, "Sett opp fingeravtrykk eller skjermlås i telefonens innstillinger først.", Toast.LENGTH_LONG
        ).show()
        return
    }
    val prompt = BiometricPrompt(
        act, ContextCompat.getMainExecutor(act),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onOk()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) {
                    Toast.makeText(ctx, errString, Toast.LENGTH_LONG).show()
                }
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle("Bekreft med fingeravtrykk")
        .setAllowedAuthenticators(allowed)
    if (Build.VERSION.SDK_INT < 30) info.setNegativeButtonText("Avbryt")
    prompt.authenticate(info.build())
}

// ───────────────────────── Skykopi (kryptert) ─────────────────────────

/** One item in the cloud. [json] is null when the item has been deleted. */
class CloudItem(val json: String?, val updated: Long)

/**
 * Backs everything up to Firebase: habits, check-ins, notes and failure answers. Each item is
 * encrypted on the phone with a key made from a secret recovery code, and stored under a path made
 * from the same code. Without the code the cloud copy is unreadable, so write the code down.
 */
class Cloud(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    var code by mutableStateOf(prefs.getString("code", "") ?: "")
        private set

    /** False when the app was built without google-services.json. */
    val available: Boolean
        get() = try {
            FirebaseApp.getInstance()
            true
        } catch (e: Exception) {
            false
        }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** True once the recovery code has been shown to the user. */
    val seen: Boolean get() = prefs.getBoolean("seen", false)

    fun markSeen() { prefs.edit().putBoolean("seen", true).apply() }

    fun displayCode(): String = code.chunked(4).joinToString("-")

    fun ensureCode(): String {
        if (code.isEmpty()) {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            val c = (1..20).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            prefs.edit().putString("code", c).apply()
            code = c
        }
        return code
    }

    /** Uses a code from another install, to fetch its data. Returns false if the code is too short. */
    fun switchCode(raw: String): Boolean {
        val c = norm(raw)
        if (c.length < 16) return false
        prefs.edit().putString("code", c).apply()
        code = c
        return true
    }

    fun isValidInput(raw: String) = norm(raw).length >= 16

    private fun keyBytes(c: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(("key:$c").toByteArray(Charsets.UTF_8))

    private fun pathId(c: String): String =
        MessageDigest.getInstance("SHA-256").digest(("path:$c").toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }

    private fun entries(c: String) =
        FirebaseFirestore.getInstance().collection("journal").document(pathId(c)).collection("entries")

    private fun enc(plain: String, key: ByteArray): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun dec(b64: String, key: ByteArray): String? = try {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Uploads one item. [json] null uploads a deletion. Does nothing until a code exists. */
    fun push(key: String, json: String?, updated: Long) {
        if (!available || code.isEmpty()) return
        try {
            val c = code
            val doc = entries(c).document(key)
            if (json == null) {
                doc.set(mapOf("data" to "", "del" to true, "updated" to updated))
            } else {
                doc.set(mapOf("data" to enc(json, keyBytes(c)), "del" to false, "updated" to updated))
            }
        } catch (ex: Exception) { }
    }

    private val legacyDay = Regex("\\d{4}-\\d{2}-\\d{2}")

    /** Reads everything from the server (fails when offline) and decrypts it. */
    fun fetch(onOk: (Map<String, CloudItem>) -> Unit, onErr: () -> Unit) {
        try {
            val c = code
            val key = keyBytes(c)
            entries(c).get(Source.SERVER)
                .addOnSuccessListener { snap ->
                    val out = HashMap<String, CloudItem>()
                    for (d in snap.documents) {
                        val updated = d.getLong("updated") ?: 0L
                        if (d.getBoolean("del") == true) {
                            out[d.id] = CloudItem(null, updated)
                            continue
                        }
                        val data = d.getString("data") ?: continue
                        val plain = dec(data, key) ?: continue
                        if (legacyDay.matches(d.id)) {
                            // Older version: one plain-text journal entry per day.
                            val id = "legacy-" + d.id
                            val j = JSONObject().put("id", id).put("date", d.id).put("title", "")
                                .put("text", plain).put("created", updated).toString()
                            out["note_$id"] = CloudItem(j, updated)
                        } else {
                            out[d.id] = CloudItem(plain, updated)
                        }
                    }
                    onOk(out)
                }
                .addOnFailureListener { onErr() }
        } catch (e: Exception) {
            onErr()
        }
    }
}

// ───────────────────────── Skriveflater ─────────────────────────

/**
 * Full-screen writing page. It sits above the keyboard (imePadding), so what you type is always
 * visible, and the text area scrolls when the note gets long.
 */
@Composable
fun NoteEditor(s: Store, noteId: String?, date: LocalDate, onClose: () -> Unit) {
    val existing = noteId?.let { s.noteById(it) }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val shownDate = existing?.let { LocalDate.parse(it.date) } ?: date
    val bodyFocus = remember { FocusRequester() }

    fun finish() {
        val changed = existing == null || existing.title != title.trim() || existing.text != text.trim()
        if (hasWords(text) && changed) s.saveNote(noteId, date, title, text)
        onClose()
    }

    LaunchedEffect(Unit) { if (existing == null) bodyFocus.requestFocus() }
    BackHandler { finish() }

    Column(Modifier.fillMaxSize().background(P.bg).imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("left") { finish() }
            Gap(w = 4)
            T(dateTitle(shownDate), 14, FontWeight.Medium, P.sub, Modifier.weight(1f), maxLines = 1)
            if (existing != null) IconBtn("trash") { confirmDelete = true }
            Gap(w = 4)
            PillButton("Ferdig") { finish() }
        }
        BasicTextField(
            value = title, onValueChange = { title = it }, singleLine = true,
            textStyle = TextStyle(color = P.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            keyboardOptions = CAPS.copy(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { bodyFocus.requestFocus() }),
            cursorBrush = SolidColor(P.accent), modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (title.isEmpty()) T("Tittel", 24, FontWeight.SemiBold, P.sub.copy(alpha = 0.5f))
                    inner()
                }
            }
        )
        Gap(h = 4)
        if (!hasWords(text)) T("Skriv minst ett ord for å krysse av vanen.", 12, color = P.sub)
        Gap(h = 10)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = P.text, fontSize = 17.sp, lineHeight = 25.sp),
                keyboardOptions = CAPS, cursorBrush = SolidColor(P.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(bottom = 24.dp).focusRequester(bodyFocus),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) T("Hva tenker du på i dag?", 17, color = P.sub.copy(alpha = 0.6f))
                        inner()
                    }
                }
            )
        }
    }
    if (confirmDelete) Sheet(onDismiss = { confirmDelete = false }) {
        T("Slette notisen?", 20, FontWeight.SemiBold)
        Gap(h = 8)
        T("Notisen slettes fra telefonen og fra skykopien.", 14, color = P.sub)
        Gap(h = 20)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Avbryt", Modifier.weight(1f), filled = false) { confirmDelete = false }
            PillButton("Slett", Modifier.weight(1f), color = P.danger) {
                confirmDelete = false
                if (noteId != null) s.deleteNote(noteId)
                onClose()
            }
        }
    }
}

// ───────────────────────── Når en auto-vane fjernes ─────────────────────────

@Composable
fun FailureDialog(h: Habit, date: LocalDate, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onDismiss)

    Column(Modifier.fillMaxSize().background(P.bg).imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("left", onClick = onDismiss)
            Gap(w = 4)
            T(h.icon + "  " + h.title, 14, FontWeight.Medium, P.sub, Modifier.weight(1f), maxLines = 1)
            PillButton("Lagre", enabled = hasWords(text)) { onSave(text) }
        }
        Gap(h = 6)
        T("Hva skjedde og hvordan kan du unngå at dette skjer i fremtiden?", 22, FontWeight.SemiBold, spacing = -0.3f)
        Gap(h = 6)
        T(dateTitle(date) + ". Svaret lagres på telefonen og i den krypterte sikkerhetskopien.", 12, color = P.sub)
        Gap(h = 14)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = P.text, fontSize = 17.sp, lineHeight = 25.sp),
                keyboardOptions = CAPS, cursorBrush = SolidColor(P.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(bottom = 24.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) T("Skriv her…", 17, color = P.sub.copy(alpha = 0.6f))
                        inner()
                    }
                }
            )
        }
    }
}

// ───────────────────────── Privat (notater + feil) ─────────────────────────

@Composable
fun PrivateScreen(s: Store, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Pair<String?, LocalDate>?>(null) }
    var showBackup by remember { mutableStateOf(false) }
    var deleteFailure by remember { mutableStateOf<Failure?>(null) }
    val today = LocalDate.now()
    val notes = s.notes.sortedWith(compareByDescending<Note> { n -> n.date }.thenByDescending { n -> n.created })
    val fails = s.failures.sortedByDescending { f -> f.created }
    val shape = RoundedCornerShape(22.dp)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn("left", onClick = onClose)
                        Gap(w = 4)
                        T("Privat", 30, FontWeight.SemiBold, modifier = Modifier.weight(1f), spacing = -0.8f)
                    }
                    Gap(h = 12)
                    Segmented(listOf("Journal", "Når jeg feilet"), tab) { tab = it }
                    Gap(h = 4)
                }
            }
            if (tab == 0) {
                item {
                    PillButton("Ny notis", Modifier.fillMaxWidth()) { editing = null to today }
                }
                if (notes.isEmpty()) {
                    item { Empty("Ingen notater ennå", "Det du skriver i journalen vises her, og kan leses igjen senere.") }
                } else {
                    items(notes, key = { n -> n.id }) { n ->
                        val d = LocalDate.parse(n.date)
                        Column(
                            Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape)
                                .tap { editing = n.id to d }.padding(16.dp)
                        ) {
                            T(dateTitle(d), 12, FontWeight.SemiBold, P.accent)
                            if (n.title.isNotBlank()) {
                                Gap(h = 4)
                                T(n.title, 17, FontWeight.SemiBold, maxLines = 2)
                            }
                            Gap(h = 4)
                            T(n.text, 15, color = if (n.title.isNotBlank()) P.sub else P.text, maxLines = 4)
                        }
                    }
                }
            } else {
                if (fails.isEmpty()) {
                    item { Empty("Ingenting her ennå", "Når du fjerner krysset på en automatisk vane, havner svaret ditt her.") }
                } else {
                    items(fails, key = { f -> f.id }) { f ->
                        val d = LocalDate.parse(f.date)
                        Column(
                            Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape).padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    T(f.habitTitle, 15, FontWeight.SemiBold, maxLines = 1)
                                    T(dateTitle(d), 12, color = P.sub)
                                }
                                IconBtn("trash") { deleteFailure = f }
                            }
                            Gap(h = 8)
                            T(f.text, 15)
                        }
                    }
                }
            }
            item {
                T(
                    "Sikkerhetskopi og gjenoppretting", 14, FontWeight.Medium, P.accent,
                    Modifier.tap { showBackup = true }.padding(top = 10.dp, bottom = 6.dp)
                )
            }
        }

        editing?.let { (id, d) -> NoteEditor(s, id, d) { editing = null } }
    }

    if (showBackup) BackupSheet(s) { showBackup = false }
    deleteFailure?.let { f ->
        Sheet(onDismiss = { deleteFailure = null }) {
            T("Slette svaret?", 20, FontWeight.SemiBold)
            Gap(h = 8)
            T("Svaret slettes fra telefonen og fra skykopien.", 14, color = P.sub)
            Gap(h = 20)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Avbryt", Modifier.weight(1f), filled = false) { deleteFailure = null }
                PillButton("Slett", Modifier.weight(1f), color = P.danger) { s.deleteFailure(f.id); deleteFailure = null }
            }
        }
    }
}

@Composable
fun BackupSheet(s: Store, onDismiss: () -> Unit) {
    val cloud = s.cloud
    val clip = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val available = cloud.available

    LaunchedEffect(available) {
        if (available) {
            cloud.ensureCode()
            cloud.markSeen()
        }
    }

    Sheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            T("Sikkerhetskopi", 22, FontWeight.SemiBold, spacing = -0.5f)
            Gap(h = 6)
            if (!available) {
                T(
                    "Skykopi er ikke slått på i denne versjonen av appen, fordi filen google-services.json mangler da den ble bygget. Alt ligger foreløpig bare på telefonen.",
                    14, color = P.sub
                )
                Gap(h = 18)
                PillButton("Lukk", Modifier.fillMaxWidth(), filled = false, onClick = onDismiss)
            } else {
                T(
                    "Vaner, avkrysninger, notater og svar du skriver når du feiler sikkerhetskopieres kryptert til skyen hver gang noe endres. Skriv ned koden under og ta vare på den. Uten koden kan ingen, heller ikke du, lese skykopien hvis telefonen blir ødelagt.",
                    14, color = P.sub
                )
                Gap(h = 14)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.bg)
                        .border(1.dp, P.border, RoundedCornerShape(16.dp)).padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    T(
                        if (cloud.code.isEmpty()) "…" else cloud.displayCode(), 17, FontWeight.SemiBold,
                        P.accent, align = TextAlign.Center, spacing = 1f
                    )
                }
                Gap(h = 10)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(if (copied) "Kopiert" else "Kopier kode", Modifier.weight(1f), filled = false) {
                        clip.setText(AnnotatedString(cloud.displayCode()))
                        copied = true
                    }
                    PillButton("Synkroniser nå", Modifier.weight(1f), enabled = !busy && cloud.code.isNotEmpty()) {
                        busy = true
                        status = "Synkroniserer…"
                        s.syncCloud { _, ok ->
                            busy = false
                            status = if (ok) "Ferdig. Alt er sikkerhetskopiert." else "Kunne ikke nå skyen. Sjekk nettet og prøv igjen."
                        }
                    }
                }
                Gap(h = 22)
                T("Ny telefon eller ødelagt telefon?", 15, FontWeight.SemiBold)
                Gap(h = 4)
                T("Skriv inn koden din for å hente alt tilbake. Dette bytter koden på denne telefonen.", 13, color = P.sub)
                Gap(h = 10)
                Field("Kode", input, { input = it }, placeholder = "xxxx-xxxx-xxxx-xxxx-xxxx", caps = false)
                Gap(h = 10)
                PillButton("Gjenopprett fra kode", Modifier.fillMaxWidth(), enabled = !busy && cloud.isValidInput(input)) {
                    if (cloud.switchCode(input)) {
                        busy = true
                        status = "Henter…"
                        s.syncCloud { count, ok ->
                            busy = false
                            status = when {
                                !ok -> "Kunne ikke nå skyen. Sjekk nettet og prøv igjen."
                                count == 0 -> "Fant ingenting med denne koden."
                                else -> "Hentet $count elementer."
                            }
                        }
                    }
                }
                if (status.isNotEmpty()) {
                    Gap(h = 12)
                    T(status, 13, FontWeight.Medium, P.accent)
                }
                Gap(h = 14)
                PillButton("Lukk", Modifier.fillMaxWidth(), filled = false, enabled = !busy, onClick = onDismiss)
            }
        }
    }
}
