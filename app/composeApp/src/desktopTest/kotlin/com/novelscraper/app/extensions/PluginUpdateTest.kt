package com.novelscraper.app.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which repository an update is allowed to come from.
 *
 * Matching on id alone meant a repository added later could list an installed
 * plugin's id with a higher version and have it replace the real one. That is a
 * swap, not an update: the replacement runs on the device as that source, with
 * the storage and cookies the original earned.
 */
class PluginUpdateTest {

    private fun installed(repo: String, version: String = "1.0.0") = InstalledPlugin(
        id = "acme.reader", name = "Acme", site = "https://acme.test/", lang = "en",
        version = version, repo = repo,
    )

    private fun offered(repo: String, version: String) = RepoPlugin(
        id = "acme.reader", name = "Acme", site = "https://acme.test/", lang = "en",
        version = version, url = "$repo/acme.js", repo = repo,
    )

    private val mine = "https://good.test/index.json"
    private val theirs = "https://hostile.test/index.json"

    @Test fun anUpdateFromTheSameRepositoryIsOffered() {
        val offer = offered(mine, "1.2.0")
        assertEquals(offer, Extensions.updateFor(installed(mine), listOf(offer)))
    }

    @Test fun aHigherVersionFromAnotherRepositoryIsNot() {
        assertNull(
            Extensions.updateFor(installed(mine), listOf(offered(theirs, "99.0.0"))),
            "a stranger's repository cannot replace an installed source by outbidding its version",
        )
    }

    @Test fun theRightRepositoryStillWinsWhenBothOfferIt() {
        val ours = offered(mine, "1.1.0")
        val found = Extensions.updateFor(installed(mine), listOf(offered(theirs, "99.0.0"), ours))
        assertEquals(ours, found, "the offer is taken from the repository it was installed from")
    }

    @Test fun anOlderOrEqualVersionIsNotAnUpdate() {
        assertNull(Extensions.updateFor(installed(mine), listOf(offered(mine, "1.0.0"))))
        assertNull(Extensions.updateFor(installed(mine), listOf(offered(mine, "0.9.0"))))
    }

    // Plugins installed before the repository was recorded.
    @Test fun aLegacyInstallTakesASingleUnambiguousOffer() {
        val only = offered(mine, "1.2.0")
        assertEquals(only, Extensions.updateFor(installed(repo = ""), listOf(only)))
    }

    @Test fun aLegacyInstallRefusesWhenTwoRepositoriesClaimIt() {
        assertNull(
            Extensions.updateFor(installed(repo = ""), listOf(offered(mine, "1.2.0"), offered(theirs, "99.0.0"))),
            "with no record of where it came from, guessing is the hole itself",
        )
    }
}
