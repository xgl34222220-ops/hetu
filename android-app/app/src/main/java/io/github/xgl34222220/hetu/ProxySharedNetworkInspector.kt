package io.github.xgl34222220.hetu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SharedInterfaceUi(
    val name: String,
    val state: String,
    /** A tether ingress candidate (reported tethered, or a tether-like name), never the upstream. */
    val tether: Boolean = false,
    /** Carries the default route right now: never offered as a shared interface. */
    val upstream: Boolean = false,
)

internal data class SharedClientUi(
    val ip: String,
    val mac: String,
    val iface: String,
    val state: String,
    /** From the DHCP lease (dnsmasq) when readable. */
    val hostname: String = "",
)

internal data class SharedNetworkSnapshot(
    val interfaces: List<SharedInterfaceUi> = emptyList(),
    val clients: List<SharedClientUi> = emptyList(),
    val error: String = "",
    val upstream: List<String> = emptyList(),
)

internal object ProxySharedNetworkInspector {
    private val ifaceSafe = Regex("^[A-Za-z0-9_.:@-]{1,32}$")
    private val macSafe = Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
    private val ipSafe = Regex("^[0-9A-Fa-f:.]{2,64}$")

    suspend fun inspect(context: Context): SharedNetworkSnapshot = withContext(Dispatchers.IO) {
        val command = """
            for p in /sys/class/net/*; do
              [ -d "${'$'}p" ] || continue
              n=${'$'}{p##*/}
              [ "${'$'}n" = lo ] && continue
              s=unknown
              [ ! -r "${'$'}p/operstate" ] || read -r s < "${'$'}p/operstate"
              printf 'I\t%s\t%s\n' "${'$'}n" "${'$'}s"
            done
            if command -v ip >/dev/null 2>&1; then
              ip neigh show 2>/dev/null | awk '
                NF >= 4 {
                  ip=${'$'}1; dev=""; mac=""; st=${'$'}NF;
                  for(i=2;i<=NF;i++){
                    if(${'$'}i=="dev" && i<NF)dev=${'$'}(i+1);
                    if(${'$'}i=="lladdr" && i<NF)mac=${'$'}(i+1);
                  }
                  if(dev!="" && mac!="")printf "C\t%s\t%s\t%s\t%s\n",ip,mac,dev,st;
                }'
            fi
            for a in 8.8.8.8 2001:4860:4860::8888; do
              d=${'$'}(ip route get "${'$'}a" 2>/dev/null | awk '{for(i=1;i<NF;i++) if(${'$'}i=="dev"){print ${'$'}(i+1); exit}}')
              [ -z "${'$'}d" ] || printf 'U\t%s\n' "${'$'}d"
            done
            { dumpsys tethering 2>/dev/null || dumpsys connectivity 2>/dev/null; } | head -n 600 | grep -i 'tethered' | head -n 40 | awk '{print "T\t" ${'$'}0}'
            for f in /data/misc/dhcp/dnsmasq.leases /data/misc/dhcp/dnsmasq.leases.* /data/vendor/dhcp/dnsmasq.leases; do
              [ -r "${'$'}f" ] && head -n 128 "${'$'}f" | awk '{print "L\t" ${'$'}0}'
            done
            exit 0
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) {
            return@withContext SharedNetworkSnapshot(error = result.output.ifBlank { "无法读取共享网络状态" })
        }
        val interfaces = LinkedHashMap<String, SharedInterfaceUi>()
        val clients = LinkedHashMap<String, SharedClientUi>()
        val upstream = LinkedHashSet<String>()
        val tetherText = StringBuilder()
        val leaseText = StringBuilder()
        result.output.lineSequence().forEach { line ->
            when {
                line.startsWith("U\t") -> line.removePrefix("U\t").trim().takeIf { ifaceSafe.matches(it) }?.let { upstream += it }
                line.startsWith("T\t") -> tetherText.append(line.removePrefix("T\t")).append('\n')
                line.startsWith("L\t") -> leaseText.append(line.removePrefix("L\t")).append('\n')
            }
        }
        val tethered = SharedNetworkRules.tetheredInterfaces(tetherText.toString())
        val leases = SharedNetworkRules.parseLeases(leaseText.toString()).associateBy { it.mac }
        result.output.lineSequence().forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "I" -> if (parts.size >= 3) {
                    val name = parts[1].trim()
                    if (ifaceSafe.matches(name)) {
                        interfaces[name] = SharedInterfaceUi(
                            name, parts[2].trim().ifBlank { "unknown" },
                            tether = SharedNetworkRules.tetherCandidate(name, upstream, tethered),
                            upstream = name in upstream,
                        )
                    }
                }
                "C" -> if (parts.size >= 5) {
                    val ip = parts[1].trim()
                    val mac = parts[2].trim().lowercase()
                    val iface = parts[3].trim()
                    val state = parts[4].trim()
                    if (ipSafe.matches(ip) && macSafe.matches(mac) && ifaceSafe.matches(iface) &&
                        mac != "00:00:00:00:00:00" && mac != "ff:ff:ff:ff:ff:ff"
                    ) {
                        clients["$mac|$iface"] = SharedClientUi(ip, mac, iface, state, leases[mac]?.hostname.orEmpty())
                    }
                }
            }
        }
        SharedNetworkSnapshot(
            interfaces = interfaces.values.sortedWith(compareByDescending<SharedInterfaceUi> { it.state == "up" }.thenBy { it.name }),
            clients = clients.values.sortedWith(compareBy<SharedClientUi> { it.iface }.thenBy { it.ip }),
            upstream = upstream.toList(),
        )
    }
}
