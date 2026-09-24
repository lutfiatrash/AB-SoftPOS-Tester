package com.lutfi.softpostester

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var serverUrlInput: EditText
    private lateinit var readKeyInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var activitySpinner: Spinner
    private lateinit var amountInput: EditText
    private lateinit var taxRateInput: EditText
    private lateinit var subjectInput: EditText
    private lateinit var lastTxView: TextView
    private lateinit var logView: TextView

    private var activityNames: List<String> = emptyList()
    private val logBuffer = StringBuilder()

    private val softPosLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("softpos_tester", Context.MODE_PRIVATE)

        serverUrlInput = findViewById(R.id.serverUrl)
        readKeyInput = findViewById(R.id.readKey)
        tokenInput = findViewById(R.id.token)
        activitySpinner = findViewById(R.id.activitySpinner)
        amountInput = findViewById(R.id.amount)
        taxRateInput = findViewById(R.id.taxRate)
        subjectInput = findViewById(R.id.subject)
        lastTxView = findViewById(R.id.lastTx)
        logView = findViewById(R.id.log)

        serverUrlInput.setText(prefs.getString("server_url", Config.DEFAULT_SERVER_URL))
        readKeyInput.setText(prefs.getString("read_key", Config.DEFAULT_READ_KEY))
        tokenInput.setText(prefs.getString("token", ""))
        taxRateInput.setText(prefs.getString("tax_rate", Config.DEFAULT_TAX_RATE))
        subjectInput.setText(prefs.getString("subject", Config.DEFAULT_ACCOUNTING_SUBJECT))

        findViewById<Button>(R.id.fetchTokenBtn).setOnClickListener { fetchToken() }
        findViewById<Button>(R.id.discoverBtn).setOnClickListener { discoverActivities() }
        findViewById<Button>(R.id.signInBtn).setOnClickListener { signIn() }
        findViewById<Button>(R.id.payBtn).setOnClickListener { pay() }
        findViewById<Button>(R.id.reverseBtn).setOnClickListener { reverseLast() }
        findViewById<Button>(R.id.checkBtn).setOnClickListener { checkLast() }
        findViewById<Button>(R.id.copyLogBtn).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.clearLogBtn).setOnClickListener {
            logBuffer.setLength(0)
            logView.text = ""
        }

        activitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                activityNames.getOrNull(pos)?.let { prefs.edit().putString("activity", it).apply() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        renderLastTx()
        discoverActivities()

        prefs.getString("pending_ext_id", null)?.let {
            log("Note: a payment was started earlier ($it) and its result was never recorded. " +
                "Tap 'Check last operation status' to find out what happened.")
        }
    }

    override fun onPause() {
        super.onPause()
        saveInputs()
    }

    private fun saveInputs() {
        prefs.edit()
            .putString("server_url", serverUrlInput.text.toString().trim())
            .putString("read_key", readKeyInput.text.toString().trim())
            .putString("token", tokenInput.text.toString().trim())
            .putString("tax_rate", taxRateInput.text.toString().trim())
            .putString("subject", subjectInput.text.toString().trim())
            .apply()
    }

    /* ------------------------------------------------------------ */
    /* Token                                                        */
    /* ------------------------------------------------------------ */

    private fun fetchToken() {
        saveInputs()
        val base = serverUrlInput.text.toString().trim().trimEnd('/')
        val key = readKeyInput.text.toString().trim()
        log("Fetching token from $base … (first call can take up to a minute while Render wakes up)")

        Thread {
            try {
                val url = URL("$base/api/token?key=" + URLEncoder.encode(key, "UTF-8"))
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 90_000
                conn.readTimeout = 90_000
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()

                runOnUiThread {
                    log("Token server HTTP $code:\n${maskTokenInText(pretty(body))}")
                    if (code in 200..299) {
                        try {
                            val token = JSONObject(body).optString("token")
                            if (token.isNotEmpty()) {
                                tokenInput.setText(token)
                                saveInputs()
                                toast("Token loaded")
                            }
                        } catch (e: Exception) {
                            log("Could not parse token response: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { log("Token fetch failed: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    /* ------------------------------------------------------------ */
    /* Activity discovery                                           */
    /* ------------------------------------------------------------ */

    @Suppress("DEPRECATION")
    private fun discoverActivities() {
        val info = try {
            packageManager.getPackageInfo(Config.SOFTPOS_PACKAGE, PackageManager.GET_ACTIVITIES)
        } catch (e: PackageManager.NameNotFoundException) {
            log("SoftPOS app '${Config.SOFTPOS_PACKAGE}' is not installed on this phone.")
            return
        }

        val all = info.activities?.toList() ?: emptyList()
        val exported = all.filter { it.exported }
        val launcherName = packageManager
            .getLaunchIntentForPackage(Config.SOFTPOS_PACKAGE)?.component?.className

        activityNames = (if (exported.isNotEmpty()) exported else all).map { it.name }

        val labels = activityNames.map { name ->
            val short = name.removePrefix(Config.SOFTPOS_PACKAGE)
            if (name == launcherName) "$short  (launcher)" else short
        }
        activitySpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val saved = prefs.getString("activity", null)
        val preferred = saved?.takeIf { it in activityNames } ?: guessActivity(launcherName)
        preferred?.let { activitySpinner.setSelection(activityNames.indexOf(it).coerceAtLeast(0)) }

        log("SoftPOS v${info.versionName}: ${all.size} activities, ${exported.size} exported.\n" +
            "Exported:\n" + exported.joinToString("\n") { "  " + it.name } +
            "\nSelected: ${preferred ?: "(none)"}")
    }

    private fun guessActivity(launcherName: String?): String? {
        val hints = listOf("external", "paybox", "integration", "intent", "api", "operation")
        for (hint in hints) {
            activityNames.firstOrNull { it.lowercase(Locale.ROOT).contains(hint) }?.let { return it }
        }
        return launcherName?.takeIf { it in activityNames } ?: activityNames.firstOrNull()
    }

    private fun selectedActivity(): String? =
        activityNames.getOrNull(activitySpinner.selectedItemPosition)

    /* ------------------------------------------------------------ */
    /* Operations                                                   */
    /* ------------------------------------------------------------ */

    private fun credentials(): JSONObject =
        JSONObject().put("authorizationToken", tokenInput.text.toString().trim())

    private fun signIn() {
        val payload = JSONObject().put("credentials", credentials())
        launch(Config.OP_SIGN_IN, payload)
    }

    private fun pay() {
        val minor = parseAmount() ?: return
        val token = tokenInput.text.toString().trim()

        val go = {
            val extId = UUID.randomUUID().toString()
            // Saved BEFORE launching, so the payment can be traced if this app dies mid-flow.
            prefs.edit()
                .putString("pending_ext_id", extId)
                .putLong("pending_amount", minor)
                .apply()

            val product = JSONObject()
                .put("name", "Sale")
                .put("price", minor.toString())
                .put("quantity", "1")
                .put("quantityExponent", "0")
                .put("taxRate", taxRateInput.text.toString().trim())
                .put("accountingSubject", subjectInput.text.toString().trim())

            val operationData = JSONObject()
                .put("instrument", "CARD")
                .put("operationExternalId", extId)
                .put("amountData", JSONObject()
                    .put("currencyCode", Config.CURRENCY_ILS)
                    .put("amount", minor)
                    .put("amountExponent", Config.AMOUNT_EXPONENT))
                .put("goods", JSONObject().put("product", JSONArray().put(product)))
                .put("needPrintReceipt", false)

            val payload = JSONObject()
                .put("credentials", credentials())
                .put("operationData", operationData)

            launch(Config.OP_PAYMENT, payload)
        }

        if (token.isEmpty()) {
            log("No token set — SoftPOS should reject this. Useful for testing the app-to-app round trip.")
            go()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Real payment")
                .setMessage("This is production. Tapping a card will charge ${formatAmount(minor)} ILS.\n\nContinue?")
                .setPositiveButton("Charge") { _, _ -> go() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun reverseLast() {
        val last = lastTx() ?: run { log("No successful payment stored to reverse."); return }

        val parent = JSONObject()
            .put("terminalId", last.optString("terminalId"))
            .put("operationDay", last.optString("operationDay"))
            .put("transactionNumber", last.optString("transactionNumber"))
        last.optString("rrn").takeIf { it.isNotEmpty() }?.let { parent.put("rrn", it) }

        val amount = last.optLong("amount")
        val operationData = JSONObject()
            .put("instrument", "CARD")
            .put("operationExternalId", UUID.randomUUID().toString())
            .put("amountData", JSONObject()
                .put("currencyCode", Config.CURRENCY_ILS)
                .put("amount", amount)
                .put("amountExponent", Config.AMOUNT_EXPONENT))
            .put("parentTransaction", parent)
            .put("needPrintReceipt", false)

        val payload = JSONObject()
            .put("credentials", credentials())
            .put("operationData", operationData)

        AlertDialog.Builder(this)
            .setTitle("Reverse payment")
            .setMessage("Reverse ${formatAmount(amount)} ILS (RRN ${last.optString("rrn")})?")
            .setPositiveButton("Reverse") { _, _ -> launch(Config.OP_REVERSAL, payload) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkLast() {
        val extId = prefs.getString("pending_ext_id", null)
            ?: lastTx()?.optString("externalId")?.takeIf { it.isNotEmpty() }
            ?: run { log("No operation ID stored to check."); return }

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -2)
        val from = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 4)
        val to = fmt.format(cal.time)

        val operationData = JSONObject()
            .put("operationExternalId", extId)
            .put("params", JSONObject().put("offset", 0).put("limit", 5))
            .put("filter", JSONObject()
                .put("dateTimeMin", from)
                .put("dateTimeMax", to)
                .put("operationExternalId", extId))

        val payload = JSONObject()
            .put("credentials", credentials())
            .put("operationData", operationData)

        launch(Config.OP_LIST, payload)
    }

    /* ------------------------------------------------------------ */
    /* Launch + result                                              */
    /* ------------------------------------------------------------ */

    private fun launch(operationType: String, payload: JSONObject) {
        saveInputs()
        val activityName = selectedActivity() ?: run {
            log("No SoftPOS activity selected. Tap 'Discover SoftPOS activities' first.")
            return
        }

        prefs.edit().putString("current_op", operationType).apply()

        val intent = Intent().apply {
            component = ComponentName(Config.SOFTPOS_PACKAGE, activityName)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Config.KEY_OPERATION_TYPE, operationType)
            putExtra(Config.KEY_INPUT_DATA, payload.toString())
        }

        log("→ $operationType\nto: $activityName\n${maskToken(payload).toString(2)}")

        try {
            softPosLauncher.launch(intent)
        } catch (e: Exception) {
            log("Launch failed: ${e.javaClass.simpleName}: ${e.message}\n" +
                "Try a different activity from the list.")
        }
    }

    private fun handleResult(resultCode: Int, data: Intent?) {
        val op = prefs.getString("current_op", "?") ?: "?"
        val codeLabel = when (resultCode) {
            Activity.RESULT_OK -> "RESULT_OK"
            Activity.RESULT_CANCELED -> "RESULT_CANCELED"
            else -> "code $resultCode"
        }
        val returnedType = data?.getStringExtra(Config.KEY_OPERATION_TYPE)
        val raw = data?.getStringExtra(Config.KEY_RESULT_DATA)
        val extraKeys = data?.extras?.keySet()?.joinToString(", ") ?: "(no extras)"

        log("← $op returned $codeLabel\n" +
            "type: ${returnedType ?: "-"}\n" +
            "extras: $extraKeys\n" +
            (raw?.let { pretty(it) } ?: "(no result data)"))

        if (raw == null) {
            if (op == Config.OP_PAYMENT) {
                log("No result data for PAYMENT. Use 'Check last operation status' to confirm the outcome.")
            }
            return
        }

        val obj = try { JSONObject(raw) } catch (e: Exception) { return }
        val result = obj.optJSONObject("result")
        val protocolCode = result?.optInt("code", -1) ?: -1
        val hostCode = result?.optJSONObject("hostResponse")?.optString("code")
        val tx = obj.optJSONObject("transaction")

        log("Summary: protocol code $protocolCode" +
            (hostCode?.let { ", host code $it" } ?: "") +
            (result?.optString("description")?.let { " — $it" } ?: ""))

        when (op) {
            Config.OP_PAYMENT -> {
                if (protocolCode == 0 && tx != null) {
                    saveLastTx(tx, prefs.getString("pending_ext_id", "") ?: "",
                        prefs.getLong("pending_amount", 0))
                }
                prefs.edit().remove("pending_ext_id").remove("pending_amount").apply()
            }
            Config.OP_REVERSAL -> {
                if (protocolCode == 0) {
                    lastTx()?.let {
                        it.put("reversed", true)
                        prefs.edit().putString("last_tx", it.toString()).apply()
                    }
                }
            }
            Config.OP_LIST -> {
                val list = obj.optJSONObject("transactions")?.optJSONArray("transaction")
                log("Operations found for that ID: ${list?.length() ?: 0}")
                val first = list?.optJSONObject(0)
                if (first != null && prefs.getString("pending_ext_id", null) != null) {
                    val status = first.optString("statusDescription")
                    log("Status of pending payment: $status")
                    if (status == "COMPLETED" && first.optString("type") == "PAYMENT") {
                        saveLastTx(first, prefs.getString("pending_ext_id", "") ?: "",
                            prefs.getLong("pending_amount", 0))
                    }
                    prefs.edit().remove("pending_ext_id").remove("pending_amount").apply()
                }
            }
        }
        renderLastTx()
    }

    /* ------------------------------------------------------------ */
    /* Last transaction storage                                     */
    /* ------------------------------------------------------------ */

    private fun saveLastTx(tx: JSONObject, extId: String, fallbackAmount: Long) {
        val isd = tx.optJSONObject("instrumentSpecificData")
        val amount = tx.optJSONObject("amountData")?.optLong("amount", fallbackAmount) ?: fallbackAmount
        val record = JSONObject()
            .put("terminalId", tx.opt("terminalId")?.toString() ?: "")
            .put("operationDay", tx.opt("operationDay")?.toString() ?: "")
            .put("transactionNumber", tx.opt("transactionNumber")?.toString() ?: "")
            .put("rrn", isd?.optString("rrn") ?: "")
            .put("authCode", isd?.optString("authorizationCode") ?: "")
            .put("maskedPan", isd?.optString("maskedPan") ?: "")
            .put("amount", amount)
            .put("externalId", extId)
            .put("savedAt", Date().toString())
            .put("reversed", false)
        prefs.edit().putString("last_tx", record.toString()).apply()
        log("Payment stored for reversal: RRN ${record.optString("rrn")}")
    }

    private fun lastTx(): JSONObject? =
        prefs.getString("last_tx", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    private fun renderLastTx() {
        val t = lastTx()
        lastTxView.text = if (t == null) "No payment yet" else
            "Last payment: ${formatAmount(t.optLong("amount"))} ILS" +
                (if (t.optBoolean("reversed")) "  [REVERSED]" else "") + "\n" +
                "card ${t.optString("maskedPan")}  auth ${t.optString("authCode")}\n" +
                "RRN ${t.optString("rrn")}\n" +
                "terminal ${t.optString("terminalId")}  day ${t.optString("operationDay")}  " +
                "txn ${t.optString("transactionNumber")}"
    }

    /* ------------------------------------------------------------ */
    /* Helpers                                                      */
    /* ------------------------------------------------------------ */

    private fun parseAmount(): Long? {
        val text = amountInput.text.toString().trim().replace(",", ".")
        return try {
            val minor = BigDecimal(text).movePointRight(Config.AMOUNT_EXPONENT)
                .setScale(0, RoundingMode.HALF_UP).toLong()
            if (minor <= 0) { toast("Enter an amount above zero"); null } else minor
        } catch (e: Exception) {
            toast("Enter a valid amount, e.g. 1.00")
            null
        }
    }

    private fun formatAmount(minor: Long): String =
        BigDecimal(minor).movePointLeft(Config.AMOUNT_EXPONENT).setScale(2).toPlainString()

    private fun maskToken(payload: JSONObject): JSONObject {
        val copy = JSONObject(payload.toString())
        copy.optJSONObject("credentials")?.let { c ->
            val t = c.optString("authorizationToken")
            c.put("authorizationToken", if (t.isEmpty()) "(empty)" else t.take(6) + "…")
        }
        return copy
    }

    private fun maskTokenInText(text: String): String =
        Regex("(\"token\"\\s*:\\s*\")([^\"]{6})[^\"]*\"").replace(text) { m ->
            m.groupValues[1] + m.groupValues[2] + "…\""
        }

    private fun pretty(s: String): String = try {
        JSONObject(s).toString(2)
    } catch (e: Exception) {
        s
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logBuffer.insert(0, "[$time] $message\n\n")
        if (logBuffer.length > 60_000) logBuffer.setLength(60_000)
        logView.text = logBuffer.toString()
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SoftPOS log", logBuffer.toString()))
        toast("Log copied")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
