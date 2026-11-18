package com.nexo.nexorith.lab.maven

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Mengambil isi sebuah URL. Mengembalikan null bila 404, melempar IOException bila gagal. */
fun interface Fetcher {
    @Throws(IOException::class)
    fun fetch(url: String): ByteArray?
}

class HttpFetcher : Fetcher {
    override fun fetch(url: String): ByteArray? {
        var last: IOException? = null
        for (attempt in 1..3) {
            try {
                return once(url)
            } catch (e: IOException) {
                last = e
                Thread.sleep(500L * attempt)
            }
        }
        throw last ?: IOException("Gagal mengunduh $url")
    }

    private fun once(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "NexorithLab/0.1")
        try {
            val code = conn.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("HTTP $code untuk $url")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
