package com.nexo.nexorith.lab

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Penggabung manifest sederhana: menambahkan izin, fitur, queries, dan komponen
 * (activity, service, receiver, provider, meta-data, ...) dari manifest tiap library AAR
 * ke manifest aplikasi. Manifest aplikasi selalu menang jika ada nama yang sama.
 */
object ManifestMerger {

    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private val TOP_LEVEL = setOf(
        "uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m",
        "uses-feature", "permission", "permission-group", "permission-tree"
    )
    private val EXPANDABLE = setOf("activity", "activity-alias", "service", "receiver", "provider")
    private val APP_PARTS = EXPANDABLE + setOf("meta-data", "uses-library", "uses-native-library", "property")

    fun readPackage(manifest: File): String? =
        Regex("""package\s*=\s*"([^"]+)"""").find(manifest.readText())?.groupValues?.get(1)

    private fun parse(f: File): Document {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        return dbf.newDocumentBuilder().parse(f)
    }

    private fun fullName(name: String, pkg: String): String = when {
        name.startsWith(".") -> pkg + name
        name.contains('.') -> name
        else -> "$pkg.$name"
    }

    private fun sig(e: Element): String {
        val sb = StringBuilder(e.tagName)
        val at = e.attributes
        val parts = ArrayList<String>()
        for (i in 0 until at.length) parts.add(at.item(i).nodeName + "=" + at.item(i).nodeValue)
        parts.sort()
        sb.append(parts.joinToString(",", "[", "]"))
        var c = e.firstChild
        while (c != null) {
            if (c is Element) sb.append(sig(c))
            c = c.nextSibling
        }
        return sb.toString()
    }

    private fun placeholders(e: Element, pkg: String) {
        val token = "\${applicationId}"
        val at = e.attributes
        for (i in 0 until at.length) {
            val n = at.item(i)
            val v = n.nodeValue
            if (v != null && v.contains(token)) n.nodeValue = v.replace(token, pkg)
        }
        var c = e.firstChild
        while (c != null) {
            if (c is Element) placeholders(c, pkg)
            c = c.nextSibling
        }
    }

    /**
     * Menggabungkan manifest lalu menulis hasilnya ke [out].
     * [finalPackage] (bila ada) dipakai sebagai atribut package (applicationId).
     */
    fun merge(app: File, libs: List<File>, finalPackage: String?, out: File) {
        val doc = parse(app)
        val root = doc.documentElement
        if (finalPackage != null) root.setAttribute("package", finalPackage)
        val pkg = root.getAttribute("package")
        if (pkg.isNullOrEmpty()) {
            throw IllegalStateException("AndroidManifest.xml tidak punya atribut package, dan applicationId/namespace tidak ditemukan di build.gradle.")
        }

        // elemen <application> (dibuat jika belum ada)
        var appEl: Element? = null
        var c = root.firstChild
        while (c != null) {
            if (c is Element && c.tagName == "application") { appEl = c; break }
            c = c.nextSibling
        }
        if (appEl == null) {
            appEl = doc.createElement("application")
            root.appendChild(appEl)
        }
        val application: Element = appEl!!

        val topKeys = HashSet<String>()
        val partKeys = HashSet<String>()
        c = root.firstChild
        while (c != null) {
            if (c is Element && c.tagName in TOP_LEVEL) {
                topKeys.add(c.tagName + "|" + c.getAttributeNS(ANDROID, "name"))
            }
            c = c.nextSibling
        }
        c = application.firstChild
        while (c != null) {
            if (c is Element && c.tagName in APP_PARTS) {
                var n = c.getAttributeNS(ANDROID, "name")
                if (c.tagName in EXPANDABLE && n.isNotEmpty()) n = fullName(n, pkg)
                partKeys.add(c.tagName + "|" + n)
            }
            c = c.nextSibling
        }

        for (lf in libs) {
            val ldoc = try { parse(lf) } catch (e: Exception) { continue }
            val lroot = ldoc.documentElement
            val lpkg = lroot.getAttribute("package")
            var n = lroot.firstChild
            while (n != null) {
                if (n is Element) {
                    when {
                        n.tagName in TOP_LEVEL -> {
                            val k = n.tagName + "|" + n.getAttributeNS(ANDROID, "name")
                            if (topKeys.add(k)) root.insertBefore(doc.importNode(n, true), application)
                        }
                        n.tagName == "queries" -> mergeQueries(doc, root, application, n)
                        n.tagName == "application" -> mergeApplication(doc, application, n, lpkg, pkg, partKeys)
                    }
                }
                n = n.nextSibling
            }
        }
        placeholders(root, pkg)

        val tf = TransformerFactory.newInstance().newTransformer()
        tf.setOutputProperty(OutputKeys.INDENT, "yes")
        tf.setOutputProperty(OutputKeys.ENCODING, "utf-8")
        out.parentFile?.mkdirs()
        tf.transform(DOMSource(doc), StreamResult(out))
    }

    private fun mergeQueries(doc: Document, root: Element, application: Element, lib: Element) {
        var q: Element? = null
        var c = root.firstChild
        while (c != null) {
            if (c is Element && c.tagName == "queries") { q = c; break }
            c = c.nextSibling
        }
        if (q == null) {
            q = doc.createElement("queries")
            root.insertBefore(q, application)
        }
        val target: Element = q!!
        val seen = HashSet<String>()
        c = target.firstChild
        while (c != null) { if (c is Element) seen.add(sig(c)); c = c.nextSibling }
        c = lib.firstChild
        while (c != null) {
            if (c is Element && seen.add(sig(c))) target.appendChild(doc.importNode(c, true))
            c = c.nextSibling
        }
    }

    private fun mergeApplication(
        doc: Document, application: Element, lib: Element,
        lpkg: String, pkg: String, partKeys: HashSet<String>
    ) {
        var a = lib.firstChild
        while (a != null) {
            if (a is Element && a.tagName in APP_PARTS) {
                val imported = doc.importNode(a, true) as Element
                if (a.tagName in EXPANDABLE) {
                    val n = imported.getAttributeNS(ANDROID, "name")
                    if (n.isNotEmpty()) imported.setAttributeNS(ANDROID, "android:name", fullName(n, lpkg))
                    if (a.tagName == "activity-alias") {
                        val t = imported.getAttributeNS(ANDROID, "targetActivity")
                        if (t.isNotEmpty()) imported.setAttributeNS(ANDROID, "android:targetActivity", fullName(t, lpkg))
                    }
                }
                placeholders(imported, pkg)
                val k = a.tagName + "|" + imported.getAttributeNS(ANDROID, "name")
                if (partKeys.add(k)) application.appendChild(imported)
            }
            a = a.nextSibling
        }
    }
}
