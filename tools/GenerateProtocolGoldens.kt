import com.gccwebos.android.vpn.SafeProxyConfig
import java.io.File

/** Offline maintainer tool: only committed, synthetic renderer bytes; no network. */
fun main() {
    val root = File("native/bridge/testdata/protocol-contract")
    for (name in listOf("ss-minimal", "vless-reality", "hysteria2-minimal", "tuic-v5-minimal", "mixed", "vless-tls", "hysteria2-bounded-options", "tuic-v5-bounded-options", "ss-aes192")) {
        for (format in listOf("clash", "mihomo")) {
            val input = File(root, "renderer/$name.$format.yaml").readBytes()
            for (index in 0 until if (name == "mixed") 4 else 1) {
                val projected = SafeProxyConfig.project(input, index)
                File(root, "accepted/$name.$format.$index.json").writeBytes(projected)
                projected.fill(0)
            }
            input.fill(0)
        }
    }
}
