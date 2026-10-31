package app.droplink.transfer

/** How a transfer travels. */
enum class ConnectionMode {
    /** Google Nearby Connections (Bluetooth discovery, then Wi-Fi Direct / hotspot / LAN). Needs Play services. */
    NEARBY,
    /** LocalSend open protocol over the shared Wi-Fi network or a hotspot. Works on every ROM. */
    LOCALSEND;

    companion object {
        fun fromName(name: String?): ConnectionMode? = entries.firstOrNull { it.name == name }
    }
}
