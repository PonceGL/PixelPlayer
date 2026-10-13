package com.theveloper.pixelplay.data.sonos.protocol

internal object SoapEnvelope {

    /** Arguments are an ordered list because Sonos expects them in the order the action declares. */
    fun build(service: SonosService, action: String, arguments: List<Pair<String, String>>): String {
        val body = arguments.joinToString(separator = "") { (name, value) ->
            "<$name>${XmlText.escape(value)}</$name>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${service.serviceType}\">$body</u:$action></s:Body></s:Envelope>"
    }

    fun soapActionHeader(service: SonosService, action: String): String =
        "\"${service.serviceType}#$action\""
}
