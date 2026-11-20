package com.med.radiogroupe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Discussion de groupe :
 * - chaque téléphone cherche ET s'annonce en même temps (plusieurs connexions possibles) ;
 * - chaque message est chiffré avec la clé du groupe ;
 * - chaque message est relayé de téléphone en téléphone (maillage) pour aller plus loin.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_GROUP = "group"
        const val EXTRA_PASS = "pass"
        private const val MAX_HOPS = 5
        private const val MAX_LEN = 1000
        private val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }

    private lateinit var client: ConnectionsClient
    private var key: SecretKey? = null
    private lateinit var myName: String
    private lateinit var group: String
    private lateinit var serviceId: String
    private var started = false

    // État des connexions
    private val connected = LinkedHashMap<String, String>()   // endpointId -> nom
    private val verified = HashSet<String>()                    // appareils avec le bon mot de passe
    private val requesting = HashSet<String>()
    private val found = HashSet<String>()
    private val rejected = HashSet<String>()
    private val incomingNames = HashMap<String, String>()
    private val knownMembers = LinkedHashSet<String>()
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > 5000
    }
    private val handler = Handler(Looper.getMainLooper())

    // Interface
    private lateinit var statusView: TextView
    private lateinit var membersView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                startNearby()
            } else {
                setStatus("Permissions manquantes")
                addSystem("Permissions refusées : l'application ne peut pas fonctionner. Autorisez Bluetooth / Appareils à proximité / Localisation dans les paramètres.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val n = intent.getStringExtra(EXTRA_NAME)
        val g = intent.getStringExtra(EXTRA_GROUP)
        val p = intent.getStringExtra(EXTRA_PASS)
        if (n == null || g == null || p == null) {
            finish()
            return
        }
        myName = n
        group = g
        knownMembers.add(myName)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        client = Nearby.getConnectionsClient(this)
        serviceId = Crypto.serviceId(group)
        buildUi()
        setStatus("Préparation du chiffrement...")

        Thread {
            val k = Crypto.deriveKey(group, p)
            runOnUiThread {
                if (!isFinishing) {
                    key = k
                    askPermissions()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::client.isInitialized) {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Permissions

    private fun requiredPermissions(): Array<String> {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.toTypedArray()
    }

    private fun askPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startNearby() else permLauncher.launch(missing.toTypedArray())
    }

    // ---------------------------------------------------------------- Radio (Nearby)

    private fun startNearby() {
        if (started) return
        started = true
        updateStatus()
        addSystem("Vous êtes dans le groupe « $group ». Les messages sont chiffrés.")

        val adv = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(myName, serviceId, connectionCallback, adv)
            .addOnFailureListener { e -> addSystem("Erreur (annonce) : ${e.message}") }

        val disc = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(serviceId, discoveryCallback, disc)
            .addOnFailureListener { e ->
                addSystem("Erreur (recherche) : ${e.message}. Vérifiez que le Bluetooth, le Wi-Fi et la localisation sont activés.")
            }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            found.add(endpointId)
            requestConn(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            found.remove(endpointId)
            requesting.remove(endpointId)
        }
    }

    private fun requestConn(id: String) {
        if (connected.containsKey(id) || requesting.contains(id) || rejected.contains(id)) return
        requesting.add(id)
        client.requestConnection(myName, id, connectionCallback)
            .addOnFailureListener {
                requesting.remove(id)
                scheduleRetry(id)
            }
    }

    private fun scheduleRetry(id: String) {
        if (rejected.contains(id) || isFinishing) return
        handler.postDelayed({
            if (found.contains(id) && !connected.containsKey(id)) requestConn(id)
        }, (2000L..6000L).random())
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (rejected.contains(endpointId)) {
                client.rejectConnection(endpointId)
                return
            }
            incomingNames[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            requesting.remove(endpointId)
            if (result.status.isSuccess) {
                connected[endpointId] = incomingNames[endpointId] ?: "?"
                sendHello(endpointId)
                updateStatus()
            } else {
                scheduleRetry(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = connected.remove(endpointId)
            val wasVerified = verified.remove(endpointId)
            if (name != null && wasVerified) addSystem("$name est hors de portée")
            updateStatus()
            scheduleRetry(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val k = key ?: return
            val plain = Crypto.decrypt(k, bytes)
            if (plain == null) {
                rejectEndpoint(endpointId)
                return
            }
            val msg = MeshMessage.parse(String(plain, Charsets.UTF_8)) ?: return
            val firstTime = verified.add(endpointId)

            if (msg.type == MeshMessage.HELLO) {
                connected[endpointId] = msg.from
                if (firstTime) {
                    addSystem(
                        if (knownMembers.add(msg.from)) "${msg.from} a rejoint le groupe"
                        else "${msg.from} est connecté"
                    )
                }
                updateStatus()
                return
            }
            handle(msg, endpointId)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun rejectEndpoint(id: String) {
        if (!rejected.add(id)) return
        client.disconnectFromEndpoint(id)
        connected.remove(id)
        verified.remove(id)
        addSystem("Un appareil avec un mot de passe différent a été refusé")
        updateStatus()
    }

    // ---------------------------------------------------------------- Messages

    private fun handle(m: MeshMessage, fromEndpoint: String) {
        if (seen.containsKey(m.id)) return
        seen[m.id] = true
        if (m.type == MeshMessage.TEXT) {
            knownMembers.add(m.from)
            addBubble(m.from, m.text, m.time, mine = false, relayed = m.ttl < MAX_HOPS)
            updateStatus()
        }
        // Relais : on renvoie aux autres voisins pour allonger la portée
        if (m.ttl > 0) {
            val targets = verifiedPeers().filter { it != fromEndpoint }
            sendTo(targets, m.copy(ttl = m.ttl - 1))
        }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (text.length > MAX_LEN) {
            toast("Message trop long (max $MAX_LEN caractères)")
            return
        }
        val targets = verifiedPeers()
        if (targets.isEmpty()) {
            toast("Aucun membre à portée pour l'instant")
            return
        }
        val m = MeshMessage(
            MeshMessage.TEXT, UUID.randomUUID().toString(), myName, text,
            System.currentTimeMillis(), MAX_HOPS
        )
        seen[m.id] = true
        sendTo(targets, m)
        addBubble(myName, text, m.time, mine = true, relayed = false)
        input.setText("")
    }

    private fun sendHello(id: String) {
        val m = MeshMessage(
            MeshMessage.HELLO, UUID.randomUUID().toString(), myName, "",
            System.currentTimeMillis(), 0
        )
        sendTo(listOf(id), m)
    }

    private fun sendTo(ids: List<String>, m: MeshMessage) {
        val k = key ?: return
        if (ids.isEmpty()) return
        val data = Crypto.encrypt(k, m.toJson().toByteArray(Charsets.UTF_8))
        client.sendPayload(ids, Payload.fromBytes(data))
    }

    private fun verifiedPeers(): List<String> = connected.keys.filter { verified.contains(it) }

    // ---------------------------------------------------------------- Interface

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6F8"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#12355B"))
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        membersView = TextView(this).apply {
            setTextColor(Color.parseColor("#C9D6E8"))
            textSize = 13f
        }
        header.addView(statusView)
        header.addView(membersView)

        scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        scroll.addView(list)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.WHITE)
        }
        input = EditText(this).apply {
            hint = "Votre message"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage()
                    true
                } else false
            }
        }
        val sendBtn = Button(this).apply {
            text = "Envoyer"
            setOnClickListener { sendMessage() }
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(sendBtn)

        root.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(root)
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    private fun updateStatus() {
        val direct = verifiedPeers().mapNotNull { connected[it] }.distinct()
        val viaRelay = knownMembers.filter { it != myName && it !in direct }
        setStatus(
            if (direct.isEmpty()) "« $group » – recherche de membres..."
            else "« $group » – ${direct.size} membre(s) à portée"
        )
        val sb = StringBuilder("Vous : $myName")
        if (direct.isNotEmpty()) sb.append("\nÀ portée : ").append(direct.joinToString(", "))
        if (viaRelay.isNotEmpty()) sb.append("\nVus via relais : ").append(viaRelay.joinToString(", "))
        membersView.text = sb.toString()
    }

    private fun addBubble(from: String, text: String, time: Long, mine: Boolean, relayed: Boolean) {
        val who = if (mine) "Moi" else from
        val head = who + " · " + timeFmt.format(Date(time)) + (if (relayed) " · via relais" else "")
        val sb = SpannableStringBuilder(head)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, who.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n").append(text)

        val tv = TextView(this).apply {
            this.text = sb
            textSize = 15f
            setTextColor(if (mine) Color.WHITE else Color.parseColor("#1B1B1B"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (mine) Color.parseColor("#12355B") else Color.WHITE)
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = dp(6)
        }
        list.addView(tv, lp)
        scrollDown()
    }

    private fun addSystem(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6B7480"))
            setTypeface(typeface, Typeface.ITALIC)
            setPadding(dp(8), dp(6), dp(8), dp(2))
        }
        list.addView(
            tv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        scrollDown()
    }

    private fun scrollDown() {
        scroll.post { scroll.smoothScrollTo(0, list.height) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
