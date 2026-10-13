package com.theveloper.pixelplay.data.sonos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object SonosFixtures {

    /** `response_body` of a captured SOAP exchange, e.g. `AVTransport.GetPositionInfo.ok`. */
    fun soapResponseBody(name: String): String = soapField(name, "response_body")

    /** `request_body` of a captured SOAP exchange. */
    fun soapRequestBody(name: String): String = soapField(name, "request_body")

    /** Raw NOTIFY body, e.g. `AVTransport_0`. */
    fun notify(name: String): String = read("sonos/notify/$name.xml")

    private fun soapField(name: String, field: String): String {
        val json = Json.parseToJsonElement(read("sonos/soap/$name.json")).jsonObject
        return checkNotNull(json[field]) { "Fixture $name has no $field" }.jsonPrimitive.content
    }

    private fun read(path: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing fixture $path" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
