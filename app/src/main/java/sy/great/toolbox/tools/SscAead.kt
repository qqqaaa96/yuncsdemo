package sy.great.toolbox.tools

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SSC 载荷的标准 AEAD 加密/解密（构建端）。
 *
 * 使用 Android 系统自带的 JCA Provider（ChaCha20-Poly1305，RFC 8439），
 * 不手写密码学，不自创算法组合。
 *
 * 待加密的是「已混淆的 shell 脚本字节」，加密成功后把 nonce + 密文||Tag 写进产物。
 * 解密只发生在产物 ELF 里（C 端用同一算法的成熟实现）。
 */
object SscAead {

    const val KEY_SIZE = 32

    // Monocypher 3.1.3 的 crypto_lock_aead / crypto_unlock_aead
    // 用的是 24 字节 nonce，即 XChaCha20-Poly1305。
    // 两端必须严格一致，不能写 12。
    const val NONCE_SIZE = 24
    const val TAG_SIZE = 16

    /**
     * 必须用 XChaCha20-Poly1305（24 字节 nonce），与 Monocypher 对应。
     *
     * 注意：不是所有 Android ROM 的 JCA Provider 都支持这个名字。
     * 不支持时直接抱错，绝不静默降级到 12 字节 nonce
     * （降级会让两端 nonce 长度不一致，产物必然解不出来）。
     */
    private const val TRANSFORM = "XChaCha20-Poly1305"

    /** 检查当前设备是否支持 XChaCha20-Poly1305 */
    fun isSupported(): Boolean = try {
        Cipher.getInstance(TRANSFORM)
        true
    } catch (e: Throwable) {
        false
    }

    /** 加密结果 */
    data class Box(
        val nonce: ByteArray,
        val ciphertextAndTag: ByteArray,
    )

    /**
     * 加密。
     *
     * @param plaintext 明文
     * @param key 32 字节数据密钥
     * @param aad 附加认证数据（容器头），必须两端一致
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray, aad: ByteArray): Box {
        require(key.size == KEY_SIZE) { "key 必须是 32 字节" }

        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)

        // JCA 返回 ciphertext || 16 字节 Tag
        val out = cipher.doFinal(plaintext)
        require(out.size == plaintext.size + TAG_SIZE) {
            "AEAD 输出长度异常：" + out.size + "，预期 " + (plaintext.size + TAG_SIZE)
        }
        return Box(nonce, out)
    }

    /**
     * 解密（构建端一般不调，供校验 / 测试用）。
     *
     * Tag 不对必须抛错，绝不能返回部分明文。
     */
    fun decrypt(
        ciphertextAndTag: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == KEY_SIZE) { "key 必须是 32 字节" }
        require(nonce.size == NONCE_SIZE) { "nonce 必须是 12 字节" }
        require(ciphertextAndTag.size >= TAG_SIZE) { "密文长度小于 Tag" }

        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)

        return try {
            cipher.doFinal(ciphertextAndTag)
        } catch (e: AEADBadTagException) {
            throw SecurityException("SSC 载荷认证失败：密文或 AAD 被篡改", e)
        }
    }
}
