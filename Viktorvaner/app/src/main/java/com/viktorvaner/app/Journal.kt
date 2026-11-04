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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
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

/**
 * Backs the journal up to Firebase. Text is encrypted on the phone with a key made from a secret
 * recovery code, and stored under a path made from the same code. Without the code the cloud copy
 * is unreadable, so write the code down.
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

    /** Uses a code from another install, to fetch its journal. Returns false if the code is too short. */
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

    fun push(e: JEntry) {
        if (!available) return
        try {
            val c = ensureCode()
            entries(c).document(e.date).set(mapOf("data" to enc(e.text, keyBytes(c)), "updated" to e.updated))
        } catch (ex: Exception) { }
    }

    fun remove(date: String) {
        if (!available || code.isEmpty()) return
        try {
            entries(code).document(date).delete()
        } catch (ex: Exception) { }
    }

    /** Reads everything from the server (fails when offline) and decrypts it. */
    fun fetch(onOk: (Map<String, JEntry>) -> Unit, onErr: () -> Unit) {
        try {
            val c = code
            val key = keyBytes(c)
            entries(c).get(Source.SERVER)
                .addOnSuccessListener { snap ->
                    val out = HashMap<String, JEntry>()
                    for (d in snap.documents) {
                        val data = d.getString("data") ?: continue
                        val text = dec(data, key) ?: continue
                        out[d.id] = JEntry(d.id, text, d.getLong("updated") ?: 0L)
                    }
                    onOk(out)
                }
                .addOnFailureListener { onErr() }
        } catch (e: Exception) {
            onErr()
        }
    }
}

// ───────────────────────── Små komponenter ─────────────────────────

@Composable
fun TextArea(value: String, onChange: (String) -> Unit, placeholder: String, minH: Int = 170) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = minH.dp).clip(shape).background(P.bg)
            .border(1.dp, P.border, shape).padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        if (value.isEmpty()) T(placeholder, 16, color = P.sub.copy(alpha = 0.7f))
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = TextStyle(color = P.text, fontSize = 16.sp, lineHeight = 23.sp),
            cursorBrush = SolidColor(P.accent), modifier = Modifier.fillMaxWidth()
        )
    }
}

// ───────────────────────── Skriv journal ─────────────────────────

@Composable
fun JournalEditor(s: Store, date: LocalDate, onDismiss: () -> Unit) {
    val existing = s.entryText(date)
    var text by remember { mutableStateOf(existing) }
    var confirmDelete by remember { mutableStateOf(false) }
    Sheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            T(dateTitle(date), 20, FontWeight.SemiBold, spacing = -0.3f)
            Gap(h = 4)
            T("Skriv minst ett ord for å krysse av vanen.", 13, color = P.sub)
            Gap(h = 14)
            TextArea(text, { text = it }, "Hva tenker du på i dag?")
            Gap(h = 18)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Avbryt", Modifier.weight(1f), filled = false, onClick = onDismiss)
                PillButton("Lagre", Modifier.weight(1f), enabled = hasWords(text)) {
                    s.saveEntry(date, text)
                    onDismiss()
                }
            }
            if (existing.isNotEmpty()) {
                T(
                    "Slett innlegg", 14, FontWeight.Medium, P.danger,
                    Modifier.tap { confirmDelete = true }.padding(top = 14.dp, bottom = 2.dp)
                )
            }
        }
    }
    if (confirmDelete) Sheet(onDismiss = { confirmDelete = false }) {
        T("Slette innlegget?", 20, FontWeight.SemiBold)
        Gap(h = 8)
        T("Innlegget slettes fra telefonen og fra skykopien, og vanen blir ikke lenger krysset av for denne dagen.", 14, color = P.sub)
        Gap(h = 20)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Avbryt", Modifier.weight(1f), filled = false) { confirmDelete = false }
            PillButton("Slett", Modifier.weight(1f), color = P.danger) {
                confirmDelete = false
                s.deleteEntry(date)
                onDismiss()
            }
        }
    }
}

// ───────────────────────── Når en auto-vane fjernes ─────────────────────────

@Composable
fun FailureDialog(h: Habit, date: LocalDate, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Sheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            T(h.icon + "  " + h.title, 13, FontWeight.Medium, P.sub, maxLines = 1)
            Gap(h = 6)
            T("Hva skjedde og hvordan kan du unngå at dette skjer i fremtiden?", 20, FontWeight.SemiBold, spacing = -0.3f)
            Gap(h = 6)
            T("Svaret lagres bare på denne telefonen.", 13, color = P.sub)
            Gap(h = 14)
            TextArea(text, { text = it }, "Skriv her…", minH = 140)
            Gap(h = 18)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Avbryt", Modifier.weight(1f), filled = false, onClick = onDismiss)
                PillButton("Lagre", Modifier.weight(1f), enabled = hasWords(text)) { onSave(text) }
            }
        }
    }
}

// ───────────────────────── Privat (journal + feil) ─────────────────────────

@Composable
fun PrivateScreen(s: Store, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var tab by remember { mutableIntStateOf(0) }
    var editDate by remember { mutableStateOf<LocalDate?>(null) }
    var showBackup by remember { mutableStateOf(false) }
    var deleteFailure by remember { mutableStateOf<Failure?>(null) }
    val today = LocalDate.now()
    val entries = s.journal.values.filter { e -> hasWords(e.text) }.sortedByDescending { e -> e.date }
    val fails = s.failures.sortedByDescending { f -> f.created }
    val shape = RoundedCornerShape(22.dp)

    // First visit: show the recovery code so it gets written down.
    LaunchedEffect(Unit) { if (s.cloud.available && !s.cloud.seen) showBackup = true }

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
            if (!s.hasEntry(today)) {
                item {
                    PillButton("Skriv i dag", Modifier.fillMaxWidth()) { editDate = today }
                }
            }
            if (entries.isEmpty()) {
                item { Empty("Ingen innlegg ennå", "Det du skriver i journalen vises her, og kan leses igjen senere.") }
            } else {
                items(entries, key = { e -> e.date }) { e ->
                    val d = LocalDate.parse(e.date)
                    Column(
                        Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape)
                            .tap { editDate = d }.padding(16.dp)
                    ) {
                        T(dateTitle(d), 13, FontWeight.SemiBold, P.accent)
                        Gap(h = 6)
                        T(e.text, 15, maxLines = 8)
                    }
                }
            }
            item {
                T(
                    "Sikkerhetskopi og gjenoppretting", 14, FontWeight.Medium, P.accent,
                    Modifier.tap { showBackup = true }.padding(top = 10.dp, bottom = 6.dp)
                )
            }
        } else {
            if (fails.isEmpty()) {
                item { Empty("Ingenting her ennå", "Når du fjerner krysset på en automatisk vane, havner svaret ditt her. Det lagres bare på denne telefonen.") }
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
    }

    editDate?.let { d -> JournalEditor(s, d) { editDate = null } }
    if (showBackup) BackupSheet(s) { showBackup = false }
    deleteFailure?.let { f ->
        Sheet(onDismiss = { deleteFailure = null }) {
            T("Slette svaret?", 20, FontWeight.SemiBold)
            Gap(h = 8)
            T("Svaret slettes fra telefonen.", 14, color = P.sub)
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
                    "Skykopi er ikke slått på i denne versjonen av appen, fordi filen google-services.json mangler da den ble bygget. Journalen ligger foreløpig bare på telefonen.",
                    14, color = P.sub
                )
                Gap(h = 18)
                PillButton("Lukk", Modifier.fillMaxWidth(), filled = false, onClick = onDismiss)
            } else {
                T(
                    "Journalen sikkerhetskopieres kryptert til skyen hver gang du lagrer. Skriv ned koden under og ta vare på den. Uten koden kan ingen, heller ikke du, lese skykopien hvis telefonen blir ødelagt.",
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
                    PillButton("Sikkerhetskopier nå", Modifier.weight(1f), enabled = !busy && cloud.code.isNotEmpty()) {
                        busy = true
                        status = "Synkroniserer…"
                        s.syncCloud { _, ok ->
                            busy = false
                            status = if (ok) "Ferdig. Journalen er sikkerhetskopiert." else "Kunne ikke nå skyen. Sjekk nettet og prøv igjen."
                        }
                    }
                }
                Gap(h = 22)
                T("Ny telefon eller ødelagt telefon?", 15, FontWeight.SemiBold)
                Gap(h = 4)
                T("Skriv inn koden din for å hente journalen tilbake. Dette bytter koden på denne telefonen.", 13, color = P.sub)
                Gap(h = 10)
                Field("Kode", input, { input = it }, placeholder = "xxxx-xxxx-xxxx-xxxx-xxxx")
                Gap(h = 10)
                PillButton("Gjenopprett fra kode", Modifier.fillMaxWidth(), enabled = !busy && cloud.isValidInput(input)) {
                    if (cloud.switchCode(input)) {
                        busy = true
                        status = "Henter…"
                        s.syncCloud { count, ok ->
                            busy = false
                            status = when {
                                !ok -> "Kunne ikke nå skyen. Sjekk nettet og prøv igjen."
                                count == 0 -> "Fant ingen innlegg med denne koden."
                                else -> "Hentet $count innlegg."
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
