package com.claustrum

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.claustrum.core.NativeCore

/**
 * P0 bring-up screen: proves the Rust perception core answers over JNI on-device
 * ("Rust 回話"), and runs a tiny L0 change-gate self-test with synthetic frames
 * so the aHash + Hamming-distance path is exercised on real hardware.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = StringBuilder()
        report.append("claustrum · Rust 核心裝置端驗證\n")
        report.append("=".repeat(28)).append("\n\n")

        try {
            report.append("① nativeHello():\n")
            report.append("   ").append(NativeCore.nativeHello()).append("\n\n")

            // ② L0 gate self-test: identical frames → distance 0; a real change → large.
            val w = 64
            val h = 64
            val flat = ByteArray(w * h) { 0 }                      // uniform dark frame
            val split = ByteArray(w * h) { i ->                    // bright bottom half
                if ((i / w) >= h / 2) 0xFF.toByte() else 0
            }
            val sigFlat = NativeCore.frameSignature(flat, w, h)
            val sigFlat2 = NativeCore.frameSignature(flat, w, h)
            val sigSplit = NativeCore.frameSignature(split, w, h)
            val distSame = java.lang.Long.bitCount(sigFlat xor sigFlat2)
            val distChange = java.lang.Long.bitCount(sigFlat xor sigSplit)

            report.append("② L0 變化閘控 (frameSignature):\n")
            report.append("   flat  = 0x${java.lang.Long.toHexString(sigFlat)}\n")
            report.append("   split = 0x${java.lang.Long.toHexString(sigSplit)}\n")
            report.append("   distance(same)   = $distSame  (期望 0)\n")
            report.append("   distance(change) = $distChange  (期望 大)\n\n")

            val pass = distSame == 0 && distChange >= 10
            report.append(if (pass) "✅ PASS — Rust 核心在裝置端運作正常" else "⚠️ 數值異常,請檢查")
        } catch (t: Throwable) {
            report.append("❌ 載入/呼叫失敗:\n").append(t.toString())
        }

        val text = TextView(this).apply {
            setText(report.toString())
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setPadding(48, 64, 48, 64)
            setTextColor(Color.parseColor("#111111"))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            addView(text)
        }
        setContentView(scroll)
    }
}
