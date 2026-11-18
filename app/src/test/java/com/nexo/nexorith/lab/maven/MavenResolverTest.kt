package com.nexo.nexorith.lab.maven

import com.nexo.nexorith.lab.build.Dependency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

private const val BASE = "https://repo.test/"

private fun sha1(b: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

private fun pomXml(g: String, a: String, v: String, packaging: String, body: String): ByteArray =
    ("<project><modelVersion>4.0.0</modelVersion><groupId>$g</groupId><artifactId>$a</artifactId>" +
        "<version>$v</version><packaging>$packaging</packaging>$body</project>").toByteArray()

private fun deps(vararg d: String): String = "<dependencies>${d.joinToString("")}</dependencies>"

private fun dep(
    g: String,
    a: String,
    v: String? = null,
    scope: String? = null,
    optional: Boolean = false,
    exclusions: String = ""
): String = "<dependency><groupId>$g</groupId><artifactId>$a</artifactId>" +
    (if (v != null) "<version>$v</version>" else "") +
    (if (scope != null) "<scope>$scope</scope>" else "") +
    (if (optional) "<optional>true</optional>" else "") +
    (if (exclusions.isNotEmpty()) "<exclusions>$exclusions</exclusions>" else "") +
    "</dependency>"

private fun exclusion(g: String, a: String) =
    "<exclusion><groupId>$g</groupId><artifactId>$a</artifactId></exclusion>"

class FakeRepo : Fetcher {
    val files = HashMap<String, ByteArray>()
    val requested = ArrayList<String>()

    override fun fetch(url: String): ByteArray? {
        requested.add(url)
        return files[url.removePrefix(BASE)]
    }

    fun add(g: String, a: String, v: String, packaging: String = "jar", body: String = "", pom: ByteArray? = null) {
        val path = "${g.replace('.', '/')}/$a/$v/$a-$v"
        files["$path.pom"] = pom ?: pomXml(g, a, v, packaging, body)
        if (packaging != "pom") {
            val ext = if (packaging == "aar") "aar" else "jar"
            val data = "bin-$a-$v".toByteArray()
            files["$path.$ext"] = data
            files["$path.$ext.sha1"] = sha1(data).toByteArray()
        }
    }
}

class MavenResolverTest {

    private fun resolver(repo: FakeRepo) = MavenResolver(
        listOf(BASE), repo, Files.createTempDirectory("nx").toFile(), {}, { false }
    )

    private fun root(g: String, a: String, v: String?, cfg: String = "implementation") = Dependency(cfg, g, a, v)

    @Test
    fun resolvesTransitiveAndSkipsTestAndOptional() {
        val repo = FakeRepo()
        repo.add("a", "lib", "1.0", body = deps(
            dep("b", "b", "1.0"),
            dep("c", "c", "1.0", scope = "test"),
            dep("d", "d", "1.0", optional = true)
        ))
        repo.add("b", "b", "1.0")
        repo.add("c", "c", "1.0")
        repo.add("d", "d", "1.0")
        val r = resolver(repo).resolve(listOf(root("a", "lib", "1.0")))
        assertEquals(listOf("a:lib:1.0", "b:b:1.0"), r.map { it.coordinate.toString() })
        assertTrue(r.all { it.file.isFile })
    }

    @Test
    fun picksHighestVersionOnConflictAndDropsStaleBranch() {
        val repo = FakeRepo()
        repo.add("x", "x", "1.0", body = deps(dep("z", "z", "1.0")))
        repo.add("y", "y", "1.0", body = deps(dep("z", "z", "2.0")))
        repo.add("z", "z", "1.0", body = deps(dep("w", "w", "1.0")))
        repo.add("z", "z", "2.0")
        repo.add("w", "w", "1.0")
        val r = resolver(repo).resolve(listOf(root("x", "x", "1.0"), root("y", "y", "1.0")))
        assertEquals(setOf("x:x:1.0", "y:y:1.0", "z:z:2.0"), r.map { it.coordinate.toString() }.toSet())
    }

    @Test
    fun honorsExclusions() {
        val repo = FakeRepo()
        repo.add("a", "lib", "1.0", body = deps(dep("m", "m", "1.0", exclusions = exclusion("c", "c"))))
        repo.add("m", "m", "1.0", body = deps(dep("c", "c", "1.0"), dep("n", "n", "1.0")))
        repo.add("c", "c", "1.0")
        repo.add("n", "n", "1.0")
        val r = resolver(repo).resolve(listOf(root("a", "lib", "1.0")))
        assertEquals(setOf("a:lib:1.0", "m:m:1.0", "n:n:1.0"), r.map { it.coordinate.toString() }.toSet())
    }

    @Test
    fun readsParentPropertiesAndDependencyManagement() {
        val repo = FakeRepo()
        repo.files["p/parent/1.0/parent-1.0.pom"] = pomXml(
            "p", "parent", "1.0", "pom",
            "<properties><lib.version>3.1</lib.version></properties>" +
                "<dependencyManagement>${deps(dep("q", "q", "\${lib.version}"))}</dependencyManagement>"
        )
        val childPom = ("<project><modelVersion>4.0.0</modelVersion>" +
            "<parent><groupId>p</groupId><artifactId>parent</artifactId><version>1.0</version></parent>" +
            "<artifactId>child</artifactId><version>2.0</version>${deps(dep("q", "q"))}</project>").toByteArray()
        repo.add("p", "child", "2.0", pom = childPom)
        repo.add("q", "q", "3.1")
        val r = resolver(repo).resolve(listOf(root("p", "child", "2.0")))
        assertEquals(listOf("p:child:2.0", "q:q:3.1"), r.map { it.coordinate.toString() })
    }

    @Test
    fun downloadsAarForAarPackaging() {
        val repo = FakeRepo()
        repo.add("a", "ui", "1.0", packaging = "aar")
        val r = resolver(repo).resolve(listOf(root("a", "ui", "1.0")))
        assertEquals("aar", r[0].packaging)
        assertTrue(repo.requested.any { it.endsWith("ui-1.0.aar") })
    }

    @Test
    fun rejectsChecksumMismatch() {
        val repo = FakeRepo()
        repo.add("a", "lib", "1.0")
        repo.files["a/lib/1.0/lib-1.0.jar.sha1"] = "deadbeef".toByteArray()
        try {
            resolver(repo).resolve(listOf(root("a", "lib", "1.0")))
            fail("seharusnya gagal karena checksum")
        } catch (e: MavenException) {
            assertTrue(e.message!!.contains("SHA-1"))
        }
    }

    @Test
    fun failsWhenArtifactMissing() {
        val repo = FakeRepo()
        try {
            resolver(repo).resolve(listOf(root("none", "none", "1.0")))
            fail("seharusnya gagal")
        } catch (e: MavenException) {
            assertTrue(e.message!!.contains("none:none:1.0"))
        }
    }

    @Test
    fun marksCompileOnly() {
        val repo = FakeRepo()
        repo.add("a", "a", "1.0")
        repo.add("b", "b", "1.0")
        val r = resolver(repo).resolve(listOf(root("a", "a", "1.0"), root("b", "b", "1.0", "compileOnly")))
        assertFalse(r.first { it.coordinate.artifact == "a" }.compileOnly)
        assertTrue(r.first { it.coordinate.artifact == "b" }.compileOnly)
    }

    @Test
    fun usesDiskCacheOnSecondRun() {
        val repo = FakeRepo()
        repo.add("a", "lib", "1.0")
        val cache: File = Files.createTempDirectory("nx").toFile()
        MavenResolver(listOf(BASE), repo, cache, {}, { false }).resolve(listOf(root("a", "lib", "1.0")))
        val before = repo.requested.size
        MavenResolver(listOf(BASE), repo, cache, {}, { false }).resolve(listOf(root("a", "lib", "1.0")))
        assertEquals(before, repo.requested.size)
    }
}
