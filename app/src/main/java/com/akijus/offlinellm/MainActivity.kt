package com.akijus.offlinellm

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import com.akijus.offlinellm.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

data class LoraItem(val path: String, val name: String, val scale: Float = 1.0f)
data class Book(val name: String, val fileName: String, var enabled: Boolean = true, var chunks: Int = 0)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine = LlamaEngine()
    private lateinit var bg: ExecutorService
    private var modelLoaded = false
    private var selectedPath: String? = null

    // history: list of (role, text), max 20 pesan
    private val history = mutableListOf<Pair<String, String>>()
    private val maxHistory = 20

    // LoRA: bisa banyak
    private val loraFiles = mutableListOf<LoraItem>()

    // Pustaka: banyak buku txt, cari di semua yang aktif
    private val books = mutableListOf<Book>()
    private val chunkCache = mutableMapOf<String, List<String>>()
    private var strictMode = true

    private val refusalText = "Tidak ada di buku. Butuh informasi baru."

    private val stopWords = setOf(
        "yang", "dan", "di", "ke", "dari", "untuk", "dengan", "pada",
        "adalah", "itu", "ini", "saya", "kamu", "anda", "apa", "bagaimana",
        "kapan", "dimana", "siapa", "mengapa", "kenapa", "berapa", "tersebut",
        "sebagai", "dalam", "oleh", "atau", "juga", "sudah", "telah", "akan",
        "the", "and", "for", "with", "tentang", "agar", "supaya", "jika", "kalau"
    )

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
            setStatus("Status: file dipilih, klik LOAD MODEL", "Belum di-load")
            toast("Disalin ke: " + dst.absolutePath)
        } catch (e: Exception) {
            toast("Gagal salin file: " + e.message)
        }
    }

    private val pickLoras = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        var added = 0
        for (uri in uris) {
            if (addLoraFromUri(uri)) added++
        }
        updateLoraUI()
        if (added > 0) toast("$added LoRA ditambahkan")
    }

    private val pickBooks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        setBookBusy(true)
        bg.execute {
            var added = 0
            for (uri in uris) {
                if (importBookFromUri(uri)) added++
            }
            runOnUiThread {
                setBookBusy(false)
                updateBookUI()
                if (added > 0) toast("$added buku ditambahkan")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bg = Executors.newSingleThreadExecutor()
        gradientTitle(binding.tvTitle)
        gradientTitle(binding.tvDrawerTitle)

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
            setStatus("Status: loading...", "Memuat...")
            bg.execute {
                try {
                    val ok = engine.initModel(path)
                    if (ok) {
                        for (l in loraFiles) {
                            engine.addLora(l.path, l.scale)
                        }
                    }
                    modelLoaded = ok
                    runOnUiThread {
                        setBusy(false)
                        if (ok) {
                            setStatus(
                                "Status: model OK" + (if (loraFiles.isNotEmpty()) " + ${loraFiles.size} LoRA" else ""),
                                "Model aktif"
                            )
                            binding.drawerLayout.closeDrawer(GravityCompat.START)
                        } else {
                            setStatus("Status: GAGAL load model. Cek file .gguf (butuh 1-2 GB RAM bebas).", "Gagal")
                            toast("Gagal load model")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        setBusy(false)
                        setStatus("Status: error " + e.message, "Gagal")
                    }
                }
            }
        }

        binding.btnAddLora.setOnClickListener {
            pickLoras.launch(arrayOf("*/*"))
        }

        binding.btnClearLora.setOnClickListener {
            loraFiles.clear()
            bg.execute {
                runCatching { engine.clearLoras() }
                runOnUiThread { updateLoraUI() }
            }
            updateLoraUI()
            toast("Semua LoRA dihapus")
        }

        binding.btnModeToggle.setOnClickListener {
            strictMode = !strictMode
            updateModeUI()
            toast(if (strictMode) "Mode ketat: hanya dari buku" else "Mode bebas: boleh pengetahuan umum")
        }

        binding.btnAddBook.setOnClickListener {
            pickBooks.launch(arrayOf("*/*"))
        }

        binding.btnClearBooks.setOnClickListener {
            bg.execute {
                runCatching {
                    val dir = File(filesDir, "books")
                    dir.listFiles()?.forEach { it.delete() }
                    saveBookIndex(mutableListOf())
                }
                books.clear()
                chunkCache.clear()
                runOnUiThread { updateBookUI() }
            }
            books.clear()
            chunkCache.clear()
            updateBookUI()
            toast("Semua buku dihapus")
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
                    val hits = searchBooks(prompt, 3)
                    if (strictMode && hits.isEmpty()) {
                        runOnUiThread {
                            setBusy(false)
                            addMessage("AI", refusalText)
                        }
                        return@execute
                    }
                    val fullPrompt = if (strictMode) {
                        buildStrictPrompt(hits, prompt)
                    } else {
                        buildFreePrompt(hits, prompt)
                    }
                    val out = engine.generateReply(fullPrompt, 256).trim()
                    val reply = if (out.isEmpty()) refusalText else out
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
            toast("Riwayat telah dihapus.")
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        updateChatUI()
        updateLoraUI()
        updateModeUI()
        bg.execute {
            loadBookIndex()
            runOnUiThread { updateBookUI() }
        }
        updateBookUI()
    }

    // ---------- Pustaka ----------

    private fun booksDir(): File = File(filesDir, "books").apply { mkdirs() }
    private fun indexFile(): File = File(booksDir(), "index.json")

    private fun loadBookIndex() {
        try {
            books.clear()
            chunkCache.clear()
            val f = indexFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = Book(
                    o.optString("name"),
                    o.optString("file"),
                    o.optBoolean("enabled", true),
                    o.optInt("chunks", 0)
                )
                if (b.fileName.isBlank()) continue
                val bf = File(booksDir(), b.fileName)
                if (!bf.exists()) continue
                books.add(b)
                chunkCache[b.fileName] = loadChunksFor(b)
            }
        } catch (_: Exception) {
        }
    }

    private fun saveBookIndex(list: List<Book>) {
        try {
            val arr = JSONArray()
            for (b in list) {
                val o = JSONObject()
                o.put("name", b.name)
                o.put("file", b.fileName)
                o.put("enabled", b.enabled)
                o.put("chunks", b.chunks)
                arr.put(o)
            }
            indexFile().writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadChunksFor(b: Book): List<String> {
        return try {
            val cf = File(booksDir(), b.fileName + ".chunks.json")
            if (cf.exists()) {
                val arr = JSONArray(cf.readText())
                List(arr.length()) { arr.getString(it) }
            } else {
                val text = File(booksDir(), b.fileName).readText()
                val ch = chunkText(text)
                runCatching {
                    val arr = JSONArray()
                    for (c in ch) arr.put(c)
                    cf.writeText(arr.toString())
                }
                ch
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun importBookFromUri(uri: Uri): Boolean {
        return try {
            val dispName = queryDisplayName(uri) ?: ("buku-" + System.currentTimeMillis() + ".txt")
            val safeName = dispName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
            val finalName = if (safeName.lowercase().endsWith(".txt")) safeName else "$safeName.txt"
            val dst = File(booksDir(), System.currentTimeMillis().toString() + "_" + finalName)
            var bytes = 0L
            contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        bytes += n
                        if (bytes > 25 * 1024 * 1024) throw Exception("file terlalu besar (maks 25 MB)")
                        out.write(buf, 0, n)
                    }
                }
            }
            val text = dst.readText()
            if (text.isBlank()) {
                dst.delete()
                return false
            }
            val chunks = chunkText(text)
            if (chunks.isEmpty()) {
                dst.delete()
                return false
            }
            val b = Book(dispName, dst.name, true, chunks.size)
            books.add(b)
            chunkCache[b.fileName] = chunks
            runCatching {
                val arr = JSONArray()
                for (c in chunks) arr.put(c)
                File(booksDir(), b.fileName + ".chunks.json").writeText(arr.toString())
            }
            saveBookIndex(books)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun chunkText(text: String, maxLen: Int = 600, overlap: Int = 100): List<String> {
        val clean = text.replace("\r\n", "\n").replace("\r", "\n")
        val paras = clean.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                out.add(cur.toString().trim())
                val tail = cur.toString().takeLast(overlap)
                cur.clear()
                cur.append(tail).append("\n")
            }
        }
        for (p in paras) {
            if (p.length > maxLen) {
                var i = 0
                while (i < p.length) {
                    val end = minOf(i + maxLen, p.length)
                    val piece = p.substring(i, end).trim()
                    if (piece.isNotEmpty()) {
                        if (cur.length + piece.length + 1 > maxLen) flush()
                        if (cur.isNotEmpty()) cur.append("\n")
                        cur.append(piece)
                        if (cur.length >= maxLen) flush()
                    }
                    i = end
                }
            } else {
                if (cur.length + p.length + 1 > maxLen) flush()
                if (cur.isNotEmpty()) cur.append("\n")
                cur.append(p)
            }
        }
        if (cur.toString().trim().length > 50) out.add(cur.toString().trim())
        return out.filter { it.length >= 50 }
    }

    private fun queryTerms(q: String): List<String> {
        return q.lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stopWords }
            .distinct()
    }

    private fun searchBooks(query: String, topK: Int): List<String> {
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        val scored = mutableListOf<Triple<String, Double, Int>>()
        for (b in books) {
            if (!b.enabled) continue
            val chunks = chunkCache[b.fileName] ?: continue
            for (c in chunks) {
                val low = c.lowercase()
                var hits = 0
                var distinct = 0
                for (t in terms) {
                    var idx = low.indexOf(t)
                    var cnt = 0
                    while (idx >= 0 && cnt < 20) {
                        cnt++
                        idx = low.indexOf(t, idx + t.length)
                    }
                    if (cnt > 0) {
                        distinct++
                        hits += cnt
                    }
                }
                if (distinct == 0) continue
                val needDistinct = minOf(2, terms.size)
                if (distinct < needDistinct) continue
                if (terms.size > 1 && hits < 2) continue
                val score = distinct * 10.0 + hits - c.length / 2000.0
                scored.add(Triple(c, score, distinct))
            }
        }
        return scored.sortedByDescending { it.second }.take(topK).map { it.first }
    }

    private fun buildStrictPrompt(hits: List<String>, question: String): String {
        val ctx = hits.joinToString("\n---\n").take(1400)
        return StringBuilder()
            .append("Jawab HANYA berdasarkan konteks berikut. Jangan gunakan pengetahuan umum.\n\n")
            .append("Konteks:\n").append(ctx).append("\n\n")
            .append("Pertanyaan: ").append(question).append("\n")
            .append("Jawaban (singkat dan rapi):\n")
            .append("Jika jawaban tidak ada di konteks, jawab persis: ").append(refusalText)
            .toString()
    }

    private fun buildFreePrompt(hits: List<String>, question: String): String {
        val sb = StringBuilder()
        if (hits.isNotEmpty()) {
            sb.append("Info tambahan dari buku:\n")
            sb.append(hits.joinToString("\n---\n").take(1200))
            sb.append("\n\n")
        }
        val start = maxOf(0, history.size - 6)
        for (i in start until history.size) {
            val (role, text) = history[i]
            if (role == "Kamu") sb.append("User: ").append(text.take(300)).append("\n")
            else sb.append("Asisten: ").append(text.take(300)).append("\n")
        }
        sb.append("User: ").append(question).append("\nAsisten:")
        return sb.toString()
    }

    private fun setBookBusy(busy: Boolean) {
        binding.btnAddBook.isEnabled = !busy
        binding.btnClearBooks.isEnabled = !busy
        binding.tvBookInfo.text = if (busy) "Buku: memuat..." else bookInfoText()
    }

    private fun bookInfoText(): String {
        val active = books.count { it.enabled }
        return "Buku: $active aktif / ${books.size} total"
    }

    private fun updateModeUI() {
        binding.tvModeInfo.text =
            if (strictMode) "Mode: Ketat (hanya dari buku)"
            else "Mode: Bebas (boleh pengetahuan umum)"
        binding.btnModeToggle.text =
            if (strictMode) "UBAH KE BEBAS" else "UBAH KE KETAT"
    }

    private fun updateBookUI() {
        binding.tvBookInfo.text = bookInfoText()
        binding.bookListCtn.removeAllViews()
        for ((idx, b) in books.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6, 0, 6)
            }
            val tv = TextView(this).apply {
                text = "${idx + 1}. ${b.name} (${b.chunks} bagian)"
                setTextColor(0xFFC9D1D9.toInt())
                textSize = 12f
            }
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val btnToggle = Button(this).apply {
                text = if (b.enabled) "NONAKTIF" else "AKTIF"
                setOnClickListener { toggleBookAt(idx) }
            }
            val btnDel = Button(this).apply {
                text = "HAPUS"
                setOnClickListener { deleteBookAt(idx) }
            }
            btnRow.addView(btnToggle)
            btnRow.addView(btnDel)
            row.addView(tv)
            row.addView(btnRow)
            binding.bookListCtn.addView(row)
        }
    }

    private fun toggleBookAt(idx: Int) {
        if (idx < 0 || idx >= books.size) return
        books[idx].enabled = !books[idx].enabled
        saveBookIndex(books)
        updateBookUI()
    }

    private fun deleteBookAt(idx: Int) {
        if (idx < 0 || idx >= books.size) return
        val b = books.removeAt(idx)
        chunkCache.remove(b.fileName)
        bg.execute {
            runCatching {
                File(booksDir(), b.fileName).delete()
                File(booksDir(), b.fileName + ".chunks.json").delete()
                saveBookIndex(books)
            }
        }
        saveBookIndex(books)
        updateBookUI()
    }

    // ---------- LoRA ----------

    private fun addLoraFromUri(uri: Uri): Boolean {
        return try {
            val dispName = queryDisplayName(uri) ?: ("lora-" + System.currentTimeMillis() + ".bin")
            val safeName = dispName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
            val dir = File(filesDir, "loras").apply { mkdirs() }
            var dst = File(dir, safeName)
            if (dst.exists()) dst = File(dir, System.currentTimeMillis().toString() + "_" + safeName)
            contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dst).use { out -> ins.copyTo(out) }
            }
            val item = LoraItem(dst.absolutePath, dst.name, 1.0f)
            loraFiles.add(item)
            if (modelLoaded) {
                bg.execute {
                    val ok = try { engine.addLora(item.path, item.scale) } catch (_: Exception) { false }
                    runOnUiThread {
                        if (!ok) toast("LoRA tersimpan tapi gagal dipasang: " + item.name)
                        updateLoraUI()
                    }
                }
            }
            true
        } catch (e: Exception) {
            toast("Gagal tambah LoRA: " + e.message)
            false
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cur: Cursor? = null
        return try {
            cur = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cur != null && cur.moveToFirst()) cur.getString(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cur?.close()
        }
    }

    private fun updateLoraUI() {
        binding.tvLoraInfo.text = "LoRA: ${loraFiles.size} terpasang" + (if (modelLoaded) "" else " (aktif setelah LOAD MODEL)")
        binding.loraListCtn.removeAllViews()
        for ((idx, item) in loraFiles.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${idx + 1}. ${item.name}"
                setTextColor(0xFFC9D1D9.toInt())
                textSize = 12f
            }
            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "X"
                setOnClickListener { removeLoraAt(idx) }
            }
            row.addView(tv)
            row.addView(btn)
            binding.loraListCtn.addView(row)
        }
    }

    private fun removeLoraAt(idx: Int) {
        if (idx < 0 || idx >= loraFiles.size) return
        loraFiles.removeAt(idx)
        bg.execute {
            runCatching {
                engine.clearLoras()
                for (l in loraFiles) engine.addLora(l.path, l.scale)
            }
            runOnUiThread { updateLoraUI() }
        }
        updateLoraUI()
    }

    // ---------- Chat ----------

    private fun buildPrompt(current: String): String {
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
        binding.chatListCtn.removeAllViews()
        binding.chatCard.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        for ((role, text) in history) {
            binding.chatListCtn.addView(if (role == "Kamu") userBubble(text) else aiBubble(text))
        }
        binding.tvHistoryInfo.text = "Riwayat disimpan: ${history.size} / $maxHistory"
        binding.scrollChat.post { binding.scrollChat.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    // Bubble user ala Gemini: kanan, abu rounded. AI: teks polos kiri.
    private fun userBubble(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFFF4F7FC.toInt())
            textSize = 14f
            setBackgroundResource(R.drawable.bubble_user)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = dp(5)
                bottomMargin = dp(5)
                marginStart = dp(48)
            }
        }
    }

    private fun aiBubble(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(0xFFC3CCD9.toInt())
            textSize = 14f
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                topMargin = dp(5)
                bottomMargin = dp(5)
                marginEnd = dp(24)
            }
        }
    }

    private fun setStatus(main: String, pill: String) {
        binding.tvStatus.text = main
        binding.tvPill.text = pill
    }

    private fun gradientTitle(tv: TextView) {
        tv.post {
            tv.paint.shader = LinearGradient(
                0f, 0f, 0f, tv.textSize,
                intArrayOf(
                    0xFFF15DF0.toInt(),
                    0xFF8B5CF6.toInt(),
                    0xFF3FD8F5.toInt()
                ),
                null, Shader.TileMode.CLAMP
            )
            tv.invalidate()
        }
    }

    private fun setBusy(busy: Boolean, generating: Boolean = false) {
        binding.btnGenerate.isEnabled = !busy
        binding.btnLoadModel.isEnabled = !busy
        binding.btnPickFile.isEnabled = !busy
        binding.btnAddLora.isEnabled = !busy
        binding.btnAddBook.isEnabled = !busy
        binding.btnGenerate.text = if (busy && generating) "..." else "↑"
        if (busy && generating) {
            val tv = TextView(this).apply {
                tag = "typing"
                text = "AI:\nMenjawab..."
                setTextColor(0xFFC3CCD9.toInt())
                textSize = 14f
            }
            binding.chatListCtn.addView(tv)
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
