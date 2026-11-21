package com.akijus.offlinellm

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.akijus.offlinellm.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = LlamaEngine()

    private val pickGgufLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importAndLoadModel(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickModel.setOnClickListener {
            pickGgufLauncher.launch("*/*")
        }

        binding.btnGenerate.setOnClickListener {
            val prompt = binding.etPrompt.text?.toString()?.trim().orEmpty()
            if (prompt.isEmpty()) {
                Toast.makeText(this, "Masukkan prompt terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!engine.isModelLoaded) {
                Toast.makeText(this, "Pilih dan muat file .gguf dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runGeneration(prompt)
        }

        binding.btnStop.setOnClickListener {
            engine.stopGeneration()
        }
    }

    private fun importAndLoadModel(uri: Uri) {
        binding.tvStatus.text = "Menyalin model ke penyimpanan lokal..."
        binding.btnPickModel.isEnabled = false
        binding.btnGenerate.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelsDir = File(filesDir, "models").apply { mkdirs() }
                val targetFile = File(modelsDir, "selected_model.gguf")

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Tidak bisa membuka file model")

                val loaded = engine.loadModel(
                    modelPath = targetFile.absolutePath,
                    nCtx = 2048,
                    nThreads = 4
                )

                withContext(Dispatchers.Main) {
                    binding.btnPickModel.isEnabled = true
                    binding.btnGenerate.isEnabled = loaded
                    binding.tvStatus.text = if (loaded) {
                        "Model siap: ${targetFile.name}"
                    } else {
                        "Gagal memuat model .gguf"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnPickModel.isEnabled = true
                    binding.tvStatus.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun runGeneration(prompt: String) {
        binding.btnGenerate.isEnabled = false
        binding.btnStop.isEnabled = true
        binding.tvOutput.text = "Memproses..."

        lifecycleScope.launch(Dispatchers.IO) {
            val result = engine.generate(prompt = prompt, maxTokens = 256)
            withContext(Dispatchers.Main) {
                binding.tvOutput.text = result
                binding.btnGenerate.isEnabled = true
                binding.btnStop.isEnabled = false
            }
        }
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}
