package com.akijus.offlinellm

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    private val pickGguf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val name = "model-picked.gguf"
            val dst = File(filesDir, name)
            contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dst).use { out -> ins.copyTo(out) }
            }
            binding.modelPathInput.setText(dst.absolutePath)
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

        binding.pickFileBtn.setOnClickListener {
            pickGguf.launch(arrayOf("*/*"))
        }

        binding.loadModelBtn.setOnClickListener {
            val path = binding.modelPathInput.text.toString().trim()
            if (path.isEmpty()) {
                toast("Isi path model .gguf dulu")
                return@setOnClickListener
            }
            setBusy(true, "Loading model...")
            binding.modelStatusEl.text = "Status: loading..."
            bg.execute {
                try {
                    val ok = engine.initModel(path)
                    modelLoaded = ok
                    runOnUiThread {
                        setBusy(false)
                        binding.modelStatusEl.text =
                            if (ok) "Status: model OK (" + path + ")"
                            else "Status: GAGAL load model. Cek path & file .gguf (butuh 1-2 GB RAM bebas)."
                        if (!ok) toast("Gagal load model")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        setBusy(false)
                        binding.modelStatusEl.text = "Status: error " + e.message
                    }
                }
            }
        }

        binding.generateBtn.setOnClickListener {
            if (!modelLoaded) {
                toast("Load model dulu")
                return@setOnClickListener
            }
            val prompt = binding.promptInput.text.toString()
            if (prompt.isBlank()) {
                toast("Isi prompt dulu")
                return@setOnClickListener
            }
            setBusy(true, "Generating...")
            binding.resultEl.text = "⏳ generating..."
            bg.execute {
                try {
                    val out = engine.generateReply(prompt, 256)
                    runOnUiThread {
                        setBusy(false)
                        binding.resultEl.text = out
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        setBusy(false)
                        binding.resultEl.text = "Error: " + e.message
                    }
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, msg: String = "") {
        binding.loadingBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.loadModelBtn.isEnabled = !busy
        binding.generateBtn.isEnabled = !busy
        if (busy && msg.isNotEmpty()) binding.resultEl.text = msg
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        bg.execute { runCatching { engine.releaseModel() } }
        bg.shutdown()
    }
}
