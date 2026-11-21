package com.akijus.offlinellm

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import com.akijus.offlinellm.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = LlamaEngine()
    private lateinit var bg: ExecutorService
    private var modelLoaded = false
    private var selectedPath: String? = null

    // history: list of (role, text), max 20 pesan
    private val history = mutableListOf<Pair<String, String>>()
    private val maxHistory = 20

    private val pickGguf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val name = "model-picked.gguf"
            val dst = File(filesDir, name)
            contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dst).use { out -> ins.copyTo(out) }
            }
            selectedPath = dst.absolutePath
            binding.tvSelectedModel.text = "File: " + dst.absolutePath
            binding.tvStatus.text = "Status: file dipilih, klik LOAD MODEL"
            toast("Disalin ke: " + dst.absolutePath)
        } catch (e: Exception) {
            toast("Gagal salin file: " + e.message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bg = Executors.newSingleThreadExecutor()

        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.btnPickFile.setOnClickListener {
            pickGguf.launch(arrayOf("*/*"))
        }

        binding.btnLoadModel.setOnClickListener {
            val path = selectedPath
            if (path.isNullOrBlank()) {
                toast("Pilih file .gguf dulu")
                return@setOnClickListener
            }
            setBusy(true)
            binding.tvStatus.text = "Status: loading..."
            bg.execute {
                try {
                    val ok = engine.initModel(path)
                    modelLoaded = ok
                    runOnUiThread {
                        setBusy(false)
                        binding.tvStatus.text =
                            if (ok) "Status: model OK"
                            else "Status: GAGAL load model. Cek file .gguf (butuh 1-2 GB RAM bebas)."
                        if (!ok) toast("Gagal load model")
                        else binding.drawerLayout.closeDrawer(GravityCompat.START)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        setBusy(false)
                        binding.tvStatus.text = "Status: error " + e.message
                    }
                }
            }
        }

        binding.btnGenerate.setOnClickListener {
            if (!modelLoaded) {
                toast("Load model dulu (menu kiri atas)")
                binding.drawerLayout.openDrawer(GravityCompat.START)
                return@setOnClickListener
            }
            val prompt = binding.etPrompt.text.toString().trim()
            if (prompt.isBlank()) {
                toast("Isi pertanyaan dulu")
                return@setOnClickListener
            }
            binding.etPrompt.setText("")
            addMessage("Kamu", prompt)
            setBusy(true, generating = true)
            bg.execute {
                try {
                    val fullPrompt = buildPrompt(prompt)
                    val out = engine.generateReply(fullPrompt, 256).trim()
                    val reply = if (out.isEmpty()) "(kosong)" else out
                    runOnUiThread {
                        setBusy(false)
                        addMessage("AI", reply)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        setBusy(false)
                        addMessage("AI", "Error: " + e.message)
                    }
                }
            }
        }

        binding.btnClearHistory.setOnClickListener {
            history.clear()
            updateChatUI()
            toast("Riwayat dihapus")
        }

        updateChatUI()
    }

    private fun buildPrompt(current: String): String {
        // sertakan riwayat terakhir sebagai konteks
        val sb = StringBuilder()
        val start = maxOf(0, history.size - 8)
        for (i in start until history.size) {
            val (role, text) = history[i]
            if (role == "Kamu") sb.append("User: ").append(text).append("\n")
            else sb.append("Asisten: ").append(text).append("\n")
        }
        sb.append("User: ").append(current).append("\nAsisten:")
        return sb.toString()
    }

    private fun addMessage(role: String, text: String) {
        history.add(role to text)
        while (history.size > maxHistory) history.removeAt(0)
        updateChatUI()
    }

    private fun updateChatUI() {
        if (history.isEmpty()) {
            binding.tvOutput.text = "Belum ada percakapan. Buka menu \u2630 di kiri atas untuk memilih dan me-load model."
        } else {
            val sb = StringBuilder()
            for ((role, text) in history) {
                if (role == "Kamu") sb.append("Kamu:\n").append(text).append("\n\n")
                else sb.append("AI:\n").append(text).append("\n\n")
            }
            binding.tvOutput.text = sb.toString().trim()
        }
        binding.tvHistoryInfo.text = "Riwayat disimpan: ${history.size} / $maxHistory pesan"
        binding.scrollChat.post { binding.scrollChat.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun setBusy(busy: Boolean, generating: Boolean = false) {
        binding.btnGenerate.isEnabled = !busy
        binding.btnLoadModel.isEnabled = !busy
        binding.btnPickFile.isEnabled = !busy
        binding.btnGenerate.text = if (busy && generating) "..." else "KIRIM"
        if (busy && generating) {
            binding.tvOutput.text = binding.tvOutput.text.toString() + "\n\nAI:\n\u23F3 generating..."
            binding.scrollChat.post { binding.scrollChat.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        bg.execute { runCatching { engine.releaseModel() } }
        bg.shutdown()
    }
}
