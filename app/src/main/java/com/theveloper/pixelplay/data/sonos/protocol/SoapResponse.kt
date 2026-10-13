package com.theveloper.pixelplay.data.sonos.protocol

internal sealed interface SoapResponse {
    data class Success(val values: Map<String, String>) : SoapResponse

    /** The speaker rejected the action. [upnpErrorCode] is null when the fault carries none. */
    data class Fault(val upnpErrorCode: Int?) : SoapResponse

    data object Malformed : SoapResponse

    companion object {
        fun parse(body: String): SoapResponse {
            val root = SafeXml.parse(body) ?: return Malformed
            val first = root.child("Body")?.children?.firstOrNull() ?: return Malformed
            if (first.name == "Fault") {
                val code = first.descendants("errorCode").firstOrNull()?.text?.trim()?.toIntOrNull()
                return Fault(code)
            }
            return Success(first.children.associate { it.name to it.text })
        }
    }
}
