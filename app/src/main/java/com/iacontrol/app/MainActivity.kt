package com.iacontrol.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @Volatile
    private var pendingResult: String = ""

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) {
                val text = r.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (text != null) {
                    web.evaluateJavascript("onVoiceResult(${JSONObject.quote(text)})", null)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF080D18.toInt()
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
    }

    // ---------- Ponte JavaScript <-> Android ----------

    inner class Bridge {
        @JavascriptInterface
        fun status(): String =
            JSONObject()
                .put("accessibility", IAAccessibilityService.instance != null)
                .put("remote", getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("remote", false))
                .toString()

        @JavascriptInterface
        fun setRemote(on: Boolean) {
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("remote", on).apply()
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        @JavascriptInterface
        fun startVoice() {
            runOnUiThread {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                try {
                    voiceLauncher.launch(i)
                } catch (e: Exception) {
                    pendingResult = "Reconhecimento de voz indisponível neste aparelho."
                }
            }
        }

        @JavascriptInterface
        fun takeResult(): String {
            val r = pendingResult
            pendingResult = ""
            return r
        }

        @JavascriptInterface
        fun runCommand(text: String, confirmed: Boolean): String =
            handleCommand(text.trim(), confirmed)
    }

    // ---------- Interpretação de comandos (versão sem IA) ----------

    private val sensitiveWords = listOf(
        "enviar", "pagar", "apagar", "excluir", "deletar",
        "transferir", "comprar", "remover", "confirmar"
    )

    private fun handleCommand(text: String, confirmed: Boolean): String {
        val t = text.lowercase(Locale("pt", "BR"))

        val clickRe = Regex("^(toque|clique|toca)\\s+(?:em|no|na)?\\s*(.+)$", RegexOption.IGNORE_CASE)
        val typeRe = Regex("^digite\\s+(.+)$", RegexOption.IGNORE_CASE)

        // Segurança: ações sensíveis pedem confirmação
        val isAction = clickRe.matches(text) || typeRe.matches(text)
        if (isAction && !confirmed && sensitiveWords.any { t.contains(it) }) {
            return "CONFIRM:Ação sensível: \"$text\". Executar mesmo assim?"
        }

        if (t.contains("configura")) {
            runOnUiThread { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            return "Abrindo as configurações..."
        }
        if (t.startsWith("abra ") || t.startsWith("abrir ")) {
            val name = t.substringAfter(" ").replace(Regex("^(o|a|os|as)\\s+"), "").trim()
            return openApp(name)
        }

        val svc = IAAccessibilityService.instance
        val needsService = t.startsWith("ler") || t.startsWith("leia") || isAction ||
            t.startsWith("role") || t == "voltar" || t == "início" || t == "inicio"

        if (needsService && svc == null) {
            return "Ative o serviço de acessibilidade primeiro (botão no painel)."
        }

        clickRe.find(text)?.let { m -> return runOnOtherApp { it.clickByText(m.groupValues[2]) } }
        typeRe.find(text)?.let { m -> return runOnOtherApp { it.typeText(m.groupValues[1]) } }

        return when {
            t.startsWith("ler") || t.startsWith("leia") -> runOnOtherApp { it.readScreen() }
            t.startsWith("role") && t.contains("cima") -> runOnOtherApp { it.scroll(false) }
            t.startsWith("role") -> runOnOtherApp { it.scroll(true) }
            t == "voltar" -> runOnOtherApp { it.back() }
            t == "início" || t == "inicio" -> svc!!.home()
            else -> "Ainda não entendi esse comando. Quando a IA estiver conectada (próxima etapa), " +
                "ela vai interpretar qualquer pedido.\n\nPor enquanto: abra <app>, abra as configurações, " +
                "ler tela, toque em <texto>, digite <texto>, role para baixo/cima, voltar, início."
        }
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().lowercase(Locale("pt", "BR")).contains(name)
        } ?: return "Não encontrei um app chamado \"$name\"."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "Não consegui abrir esse app."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runOnUiThread { startActivity(intent) }
        return "Abrindo ${match.loadLabel(pm)}..."
    }

    // Minimiza o IA Control, age no app que estava por trás e volta com o resultado.
    private fun runOnOtherApp(block: (IAAccessibilityService) -> String): String {
        val svc = IAAccessibilityService.instance ?: return "Serviço de acessibilidade inativo."
        Thread {
            runOnUiThread { moveTaskToBack(true) }
            Thread.sleep(1200)
            val result = try {
                block(svc)
            } catch (e: Exception) {
                "Erro: ${e.message}"
            }
            pendingResult = result
            svc.toast(result.take(150))
            Thread.sleep(300)
            try {
                startActivity(
                    Intent(this@MainActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            } catch (_: Exception) {
            }
        }.start()
        return "Ok! Vou minimizar o app, executar e voltar com o resultado."
    }
}
