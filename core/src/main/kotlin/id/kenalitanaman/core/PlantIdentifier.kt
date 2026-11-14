package id.kenalitanaman.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import java.util.Base64

/** Kesalahan identifikasi dengan pesan yang siap ditampilkan ke pengguna. */
class PlantIdentificationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Hasil identifikasi: data terurai plus JSON mentahnya (untuk disimpan ke riwayat). */
data class IdentificationResult(val info: PlantInfo, val rawJson: String)

/**
 * Mengirim foto tanaman ke Claude dan mengembalikan identifikasi beserta panduan perawatan.
 * Pemanggilan bersifat blocking; jalankan di thread latar belakang.
 */
class PlantIdentifier(
    private val client: AnthropicClient,
    private val model: String = DEFAULT_MODEL,
) {
    constructor(apiKey: String, model: String = DEFAULT_MODEL) :
        this(AnthropicOkHttpClient.builder().apiKey(apiKey).build(), model)

    fun identify(jpegBytes: ByteArray): IdentificationResult {
        val params = buildParams(Base64.getEncoder().encodeToString(jpegBytes))

        val response = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw PlantIdentificationException("API key tidak valid. Periksa kembali di Pengaturan.", e)
        } catch (e: PermissionDeniedException) {
            throw PlantIdentificationException("API key tidak memiliki izin untuk permintaan ini.", e)
        } catch (e: RateLimitException) {
            throw PlantIdentificationException("Terlalu banyak permintaan. Coba lagi sebentar lagi.", e)
        } catch (e: AnthropicServiceException) {
            throw PlantIdentificationException("Layanan AI bermasalah (kode ${e.statusCode()}). Coba lagi nanti.", e)
        } catch (e: AnthropicIoException) {
            throw PlantIdentificationException("Tidak dapat terhubung. Periksa koneksi internet Anda.", e)
        }

        val stop = response.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) {
            throw PlantIdentificationException("Foto ini tidak dapat dianalisis. Coba foto lain.")
        }
        if (stop == StopReason.MAX_TOKENS) {
            throw PlantIdentificationException("Jawaban terpotong. Silakan coba lagi.")
        }

        val json = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        if (json.isBlank()) {
            throw PlantIdentificationException("Tidak ada jawaban dari layanan AI. Silakan coba lagi.")
        }
        val info = try {
            PlantInfo.fromJson(json)
        } catch (e: Exception) {
            throw PlantIdentificationException("Jawaban tidak dapat dibaca. Silakan coba lagi.", e)
        }
        return IdentificationResult(info, json)
    }

    internal fun buildParams(base64Jpeg: String): MessageCreateParams {
        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .data(base64Jpeg)
                    .build(),
            )
            .build()

        val schema = JsonOutputFormat.Schema.builder()
            .additionalProperties(PlantSchema.root.mapValues { JsonValue.from(it.value) })
            .build()

        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.MEDIUM)
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            .addUserMessageOfContentBlockParams(
                listOf(
                    ContentBlockParam.ofImage(image),
                    ContentBlockParam.ofText(TextBlockParam.builder().text(USER_PROMPT).build()),
                ),
            )
            .build()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        private const val SYSTEM_PROMPT =
            "Kamu adalah ahli botani dan hortikultura yang membantu pengguna aplikasi pengenal tanaman " +
                "di Indonesia. Pengguna adalah penghobi tanaman, bukan ahli, jadi gunakan Bahasa Indonesia " +
                "yang jelas dan praktis. Sesuaikan saran perawatan dengan iklim tropis Indonesia kecuali " +
                "tanamannya jelas membutuhkan kondisi lain. Jujurlah soal ketidakpastian: bila foto kurang " +
                "jelas atau ada beberapa spesies yang mirip, pilih kandidat paling mungkin, turunkan tingkat " +
                "keyakinan, dan sebutkan kemungkinan lain di deskripsi. Informasi toksisitas menyangkut " +
                "keselamatan, jadi jangan menyatakan tanaman aman bila kamu tidak yakin."

        private const val USER_PROMPT =
            "Identifikasi tanaman pada foto ini, lalu berikan panduan perawatan dan informasi lengkapnya. " +
                "Jika foto tidak menampilkan tanaman, isi is_plant dengan false, jelaskan singkat di " +
                "description apa yang terlihat, dan kosongkan field lainnya."
    }
}
