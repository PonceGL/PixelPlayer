package com.theveloper.pixelplay.data.sonos.protocol

import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Immutable XML element with namespace prefixes stripped from names. */
internal data class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
    val text: String,
    val children: List<XmlElement>,
) {
    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }

    fun descendants(name: String): List<XmlElement> {
        val found = mutableListOf<XmlElement>()
        collectDescendants(name, found)
        return found
    }

    private fun collectDescendants(name: String, into: MutableList<XmlElement>) {
        for (child in children) {
            if (child.name == name) into.add(child)
            child.collectDescendants(name, into)
        }
    }
}

/**
 * Parses untrusted XML coming from the network. Documents declaring a DOCTYPE are rejected so
 * external entities can never be resolved, and size and nesting are capped so a hostile or broken
 * device cannot exhaust memory or the stack.
 */
internal object SafeXml {

    private const val DOCTYPE_MARKER = "<!DOCTYPE"
    const val MAX_DOCUMENT_CHARS = 8 * 1024 * 1024

    /** Real speaker documents (SOAP, events, DIDL, topology) nest fewer than ten levels. */
    private const val MAX_DEPTH = 64

    fun parse(xml: String): XmlElement? {
        if (xml.isBlank() || xml.length > MAX_DOCUMENT_CHARS || xml.contains(DOCTYPE_MARKER)) return null
        val handler = TreeBuilder()
        return try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
            factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
            handler.root
        } catch (_: SAXException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: ParserConfigurationException) {
            null
        }
    }

    private class MutableNode(val name: String, val attributes: Map<String, String>) {
        val text = StringBuilder()
        val children = mutableListOf<MutableNode>()

        fun freeze(): XmlElement = XmlElement(name, attributes, text.toString(), children.map { it.freeze() })
    }

    private class TreeBuilder : DefaultHandler() {
        private val stack = ArrayDeque<MutableNode>()
        private var rootNode: MutableNode? = null

        val root: XmlElement? get() = rootNode?.freeze()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (stack.size >= MAX_DEPTH) throw SAXException("XML nested deeper than $MAX_DEPTH levels")
            val attrs = LinkedHashMap<String, String>()
            for (i in 0 until attributes.length) {
                val attrName = attributes.getQName(i)
                if (attrName == "xmlns" || attrName.startsWith("xmlns:")) continue
                attrs[attrName.substringAfter(':')] = attributes.getValue(i)
            }
            val node = MutableNode(qName.substringAfter(':'), attrs)
            stack.lastOrNull()?.children?.add(node)
            if (rootNode == null) rootNode = node
            stack.addLast(node)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            stack.lastOrNull()?.text?.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            stack.removeLastOrNull()
        }
    }
}
