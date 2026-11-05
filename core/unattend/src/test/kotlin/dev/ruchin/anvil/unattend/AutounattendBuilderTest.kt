package dev.ruchin.anvil.unattend

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import javax.xml.parsers.DocumentBuilderFactory

class AutounattendBuilderTest {
    @Test fun `all options produce well-formed xml with the expected passes`() {
        val b = AutounattendBuilder.build(
            UnattendOptions(localAccount = LocalAccount("Ruchin & co", ""), removeBloatware = true,
                region = Region("India Standard Time", "en-IN", "en-US")),
            uiLanguage = AutounattendBuilder.languageFromIsoLabel("CCCOMA_X64FRE_EN-US_DV9"),
        )
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(b.autounattendXml.byteInputStream())
        val passes = (0 until doc.getElementsByTagNameNS("*", "settings").length).map {
            doc.getElementsByTagNameNS("*", "settings").item(it).attributes.getNamedItem("pass").nodeValue
        }
        assertThat(passes).containsExactly("windowsPE", "specialize", "oobeSystem").inOrder()
        assertThat(b.autounattendXml).contains("BypassTPMCheck")
        assertThat(b.autounattendXml).contains("<Name>Ruchin &amp; co</Name>")
        assertThat(b.autounattendXml).contains("<UILanguage>en-US</UILanguage>")
        assertThat(b.extraFiles).containsKey(AutounattendBuilder.DEBLOAT_SCRIPT_PATH)
    }

    @Test fun `user name rules`() {
        assertThat(LocalAccount.isValidName("Ruchin")).isTrue()
        assertThat(LocalAccount.isValidName("Administrator")).isFalse()
        assertThat(LocalAccount.isValidName("a/b")).isFalse()
        assertThat(LocalAccount.isValidName("x".repeat(21))).isFalse()
    }
}
