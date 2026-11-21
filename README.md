# Akijus — Android LLM Offline (Redmi 13, ARM64)

Aplikasi Android offline (llama.cpp via JNI) untuk HP Redmi 13 (arm64-v8a, RAM 8 GB).

## Cara pakai
1. Download model .gguf quant kecil (contoh: Qwen2.5 0.5B / 1.5B Q4_K_M, atau TinyLlama 1.1B Q4).
2. Taruh di HP (/sdcard/Download/...) lalu pilih via tombol "Pilih file" atau ketik path manual.
3. Load model → Generate.

Butuh RAM bebas ~1-2 GB tergantung ukuran model. Rekomendasi awal: model 0.5B-1.5B Q4.

## Build (otomatis via GitHub Actions)
Push ke branch `main` → workflow `Build Debug APK` → artifact `offline-llm-debug-apk`.
