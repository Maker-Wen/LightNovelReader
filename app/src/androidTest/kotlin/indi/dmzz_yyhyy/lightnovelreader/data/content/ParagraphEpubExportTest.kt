package indi.dmzz_yyhyy.lightnovelreader.data.content

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.text.ParagraphNode
import io.nightfish.lightnovelreader.api.text.TextNode
import io.nightfish.potatoepub.builder.SimpleContentBuilder
import io.nightfish.potatoepub.xml.asFormatedXml
import org.dom4j.DocumentHelper
import org.dom4j.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParagraphEpubExportTest {
    @Test
    fun serializedParagraphsKeepXhtmlNamespaceAndTextOrder() {
        val paragraphs = listOf(
            ParagraphNode(
                listOf(
                    TextNode("First\n"),
                    TextNode("Second\n\nThird"),
                    TextNode("\nLast"),
                )
            ),
            ParagraphNode(),
            ParagraphNode(listOf(TextNode("Next paragraph"))),
        )

        val (xml, exported) = serializeAndReparse(paragraphs)

        assertFalse(xml.contains("xmlns=\"\""))
        assertEquals(3, exported.size)
        exported.forEach { paragraph ->
            assertEquals("div", paragraph.name)
            assertEquals(XHTML_NAMESPACE, paragraph.namespaceURI)
            paragraph.elements().forEach { lineBreak ->
                assertEquals("br", lineBreak.name)
                assertEquals(XHTML_NAMESPACE, lineBreak.namespaceURI)
            }
        }
        assertEquals(
            listOf("First", "br", "Second", "br", "br", "Third", "br", "Last"),
            contentTokens(exported[0]),
        )
        assertEquals(listOf("br"), contentTokens(exported[1]))
        assertEquals(listOf("Next paragraph"), contentTokens(exported[2]))
    }

    @Test
    fun serializedTextRemainsEscapedAndXmlControlsAreRemoved() {
        val (xml, exported) = serializeAndReparse(
            listOf(
                ParagraphNode(
                    listOf(
                        TextNode("<img src=\"x\">A & B\u0000\u0008\u000B\u001F"),
                        TextNode("\n'quoted' > \u000C"),
                    )
                )
            )
        )

        assertTrue(xml.contains("&lt;img"))
        assertTrue(xml.contains("&amp;"))
        listOf('\u0000', '\u0008', '\u000B', '\u000C', '\u001F').forEach { control ->
            assertFalse(xml.contains(control))
        }
        assertEquals(listOf("br"), exported.single().elements().map(Element::getName))
        assertEquals(
            listOf("<img src=\"x\">A & B", "br", "'quoted' >"),
            contentTokens(exported.single()),
        )
    }

    private fun serializeAndReparse(paragraphs: List<ParagraphNode>): Pair<String, List<Element>> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val builder = SimpleContentBuilder().apply {
            title("Paragraph export regression")
            paragraphs.forEach { paragraph ->
                bodyElement.add(ParagraphComponentData(paragraph).toHtmlElement(context))
            }
        }
        val xml = builder.build().asFormatedXml()
        val document = DocumentHelper.parseText(xml)
        assertEquals(XHTML_NAMESPACE, document.rootElement.namespaceURI)
        val body = document.rootElement.element("body")
        assertEquals(XHTML_NAMESPACE, body.namespaceURI)
        return xml to body.elements().filter { it.attributeValue("id") != "content" }
    }

    private fun contentTokens(element: Element): List<String> = buildList {
        val text = StringBuilder()
        fun flushText() {
            val token = text.toString().trim()
            if (token.isNotEmpty()) add(token)
            text.setLength(0)
        }
        element.content().forEach { node ->
            if (node is Element) {
                flushText()
                add(node.name)
            } else {
                // Entity references can be reparsed as separate adjacent text nodes.
                text.append(node.text)
            }
        }
        flushText()
    }

    private companion object {
        const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"
    }
}
