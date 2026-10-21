package com.novelscraper.app.platform

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A cloud backup leaves the device and can be restored onto another one, so
 * nothing that unlocks anything may travel in it (androidApp's backup rules).
 */
class BackupRulesTest {

    /**
     * The preference files that hold a credential. A new one added to the app
     * has to be added here on purpose, and so to the rules.
     */
    private val secrets = listOf(
        "ns.xml",            // the old account's session cookie
        "site-cookies.xml",  // what sites set, logins included
        "plugin-data.xml",   // what sources store, tokens included
        "sync.xml",          // the sync code, the WebDAV password, the passphrase
    )

    private val xml = File("../androidApp/src/main/res/xml")

    /** The shared-preference files excluded under [section] (null: anywhere in the file). */
    private fun excluded(file: String, section: String?): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xml, file))
        val scope = section?.let { doc.getElementsByTagName(it).item(0) ?: error("$file has no <$it>") } ?: doc
        val nodes = (scope as? org.w3c.dom.Document)?.getElementsByTagName("exclude")
            ?: (scope as org.w3c.dom.Element).getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            .filter { it.getAttribute("domain") == "sharedpref" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    @Test fun noSecretTravelsInACloudBackup() {
        val old = excluded("backup_rules.xml", null)                       // Android 11 and below
        val cloud = excluded("data_extraction_rules.xml", "cloud-backup")  // Android 12 and above
        for (s in secrets) {
            assertTrue(s in old, "$s goes into backups on Android 11 and below")
            assertTrue(s in cloud, "$s goes into cloud backups on Android 12 and above")
        }
    }
}
