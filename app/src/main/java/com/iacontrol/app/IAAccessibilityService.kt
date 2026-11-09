package com.iacontrol.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.min

class IAAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: IAAccessibilityService? = null

        @Volatile
        private var generation = 0

        // Apps que o controle remoto nunca lê nem toca (bancos, carteiras, senhas).
        private val blockedPackages = listOf(
            "com.nu.production", "com.itau", "com.bradesco", "br.com.bb.android",
            "com.caixa", "br.gov.caixa", "com.santander", "br.com.intermedium",
            "com.picpay", "com.mercadopago", "com.binance", "br.com.sicredi",
            "com.c6bank", "com.google.android.apps.walletnfc",
            "com.google.android.apps.nbu.paisa", "com.bitwarden", "com.lastpass",
            "com.google.android.apps.authenticator2", "bank", "banco"
        )

        // Palavras que o controle remoto se recusa a tocar (sem confirmação possível à distância).
        private val sensitiveWords = listOf(
            "pagar", "apagar", "excluir", "deletar", "transferir",
            "comprar", "remover", "confirmar"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val mine = ++generation
        Thread { remoteLoop(mine) }.start()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- Ações ----------

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Não consegui ler a tela."
        val sb = StringBuilder()
        dump(root, sb, 0, IntArray(1))
        return if (sb.isBlank()) "Tela sem texto legível." else sb.toString().trim()
    }

    fun clickByText(text: String): String {
        val root = rootInActiveWindow ?: return "Não consegui ler a tela."
        for (node in root.findAccessibilityNodeInfosByText(text)) {
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return "Toquei em \"$text\"."
                }
                cur = cur.parent
            }
        }
        return "Não achei \"$text\" na tela."
    }

    fun typeText(text: String): String {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "Nenhum campo de texto em foco. Toque no campo primeiro."
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            "Digitei: $text" else "Não consegui digitar."
    }

    fun scroll(forward: Boolean): String {
        val root = rootInActiveWindow ?: return "Não consegui ler a tela."
        val node = findScrollable(root) ?: return "Não achei nada rolável na tela."
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (node.performAction(action)) "Rolei para ${if (forward) "baixo" else "cima"}."
        else "Não consegui rolar."
    }

    fun back(): String {
        performGlobalAction(GLOBAL_ACTION_BACK)
        return "Voltei."
    }

    fun home(): String {
        performGlobalAction(GLOBAL_ACTION_HOME)
        return "Fui para a tela inicial."
    }

    // ---------- Controle remoto (relay local) ----------

    private fun remoteLoop(mine: Int) {
        while (instance != null && generation == mine) {
            try {
                val enabled = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("remote", false)
                if (!enabled) {
                    Thread.sleep(2000)
                    continue
                }
                val conn = URL("http://127.0.0.1:8788/poll").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 30000
                try {
                    if (conn.responseCode == 200) {
                        val cmd = JSONObject(conn.inputStream.bufferedReader().readText())
                        val result = try {
                            handleRemote(cmd.getString("cmd"), cmd.optJSONObject("args") ?: JSONObject())
                        } catch (e: Exception) {
                            "Erro: ${e.message}"
                        }
                        sendResult(cmd.getString("id"), result)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun sendResult(id: String, result: String) {
        val conn = URL("http://127.0.0.1:8788/result").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.outputStream.use {
                it.write(JSONObject().put("id", id).put("result", result).toString().toByteArray())
            }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun isBlocked(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return p.isNotEmpty() && blockedPackages.any { p.contains(it) }
    }

    private fun handleRemote(cmd: String, args: JSONObject): String {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
        val free = cmd == "abrir_app" || cmd == "voltar" || cmd == "inicio"
        if (!free && isBlocked(pkg)) {
            return "Bloqueado: app sensível em primeiro plano ($pkg). Nada foi lido nem tocado."
        }
        val result = when (cmd) {
            "ler_tela" -> "App atual: $pkg\n" + readScreen()
            "abrir_app" -> openAppByName(args.optString("nome"))
            "tocar_texto" -> {
                val t = args.optString("texto")
                if (sensitiveWords.any { t.lowercase(Locale("pt", "BR")).contains(it) })
                    "Bloqueado: ação sensível (\"$t\"). O dono precisa fazer isso manualmente."
                else clickByText(t)
            }
            "digitar" -> typeText(args.optString("texto"))
            "rolar" -> scroll(args.optString("direcao") != "cima")
            "voltar" -> back()
            "inicio" -> home()
            else -> "Comando desconhecido: $cmd"
        }
        if (cmd != "ler_tela") Thread.sleep(700) // dá tempo da tela atualizar
        return result
    }

    private fun openAppByName(name: String): String {
        val n = name.lowercase(Locale("pt", "BR")).trim()
        if (n.isEmpty()) return "Informe o nome do app."
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale("pt", "BR")).contains(n)
        } ?: return "Não encontrei um app chamado \"$name\"."
        val target = match.activityInfo.packageName
        if (isBlocked(target)) return "Bloqueado: app sensível."
        val intent = pm.getLaunchIntentForPackage(target) ?: return "Não consegui abrir esse app."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Thread.sleep(1500)
        return "Abri ${match.loadLabel(pm)}."
    }

    fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Auxiliares ----------

    private fun dump(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, count: IntArray) {
        if (node == null || count[0] > 250) return
        val label = node.text?.toString() ?: node.contentDescription?.toString()
        if (!label.isNullOrBlank() || node.isClickable) {
            count[0]++
            sb.append("  ".repeat(min(depth, 6)))
            if (node.isClickable) sb.append("[botão] ")
            sb.append(label ?: node.className).append("\n")
        }
        for (i in 0 until node.childCount) dump(node.getChild(i), sb, depth + 1, count)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
