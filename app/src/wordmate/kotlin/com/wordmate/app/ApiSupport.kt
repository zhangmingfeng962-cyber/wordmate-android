package com.wordmate.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ApiFailure(message: String, val code: String = "", val status: Int = 0) : IOException(message) {
    fun accountBlocked() = code.contains("Overdue", true) || code == "insufficient_quota"
}

object ApiErrors {
    fun redact(text: String, secret: String = ""): String {
        var safe = if (secret.isNotBlank()) text.replace(secret, "[已隐藏密钥]") else text
        safe = safe.replace(Regex("(?i)(?:ark-|sk-|ak-)[a-z0-9_-]{12,}"), "[已隐藏密钥]")
        safe = safe.replace(Regex("(?i)Bearer\\s+[^\\s\"']+"), "Bearer [已隐藏密钥]")
        return safe.replace(Regex("[\\r\\n]+"), " ").take(220)
    }
    fun service(status: Int, body: String, secret: String = ""): ApiFailure {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val detail = json?.optJSONObject("error") ?: json
        val code = redact(detail?.optString("code").orEmpty(), secret)
        val message = redact(detail?.optString("message").orEmpty(), secret)
        val friendly = when {
            code.contains("Overdue", true) -> "AI 服务返回账户欠费/计费不可用，请到服务商费用中心处理"
            code == "insufficient_quota" -> "AI 服务账户额度不足，请检查余额和计费设置"
            status == 401 -> "API 密钥校验失败，请重新检查已保存的密钥"
            code.contains("ServiceNotOpen", true) -> "该模型服务尚未开通，请到服务商控制台开通模型"
            status == 403 -> "该模型访问被拒绝，请检查模型开通状态、权限和账户"
            status == 404 -> "模型或接口不存在，请核对模型名称和 API 地址"
            status == 429 -> "模型额度不足或请求过多，请查看服务商限额后重试"
            status == 400 -> "模型不接受当前参数，请核对模型能力"
            else -> "AI 服务暂时不可用"
        }
        val suffix = listOf("HTTP " + status, code).filter(String::isNotBlank).joinToString("，")
        return ApiFailure(friendly + "（" + suffix + "）" +
            if (message.isNotBlank() && !code.contains("Overdue", true)) "：" + message else "", code, status)
    }
}

object ProviderRequests {
    fun isArk(base: String) = base.toHttpUrlOrNull()?.host?.endsWith(".volces.com") == true
    fun text(base: String, model: String, messages: JSONArray): JSONObject {
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        if (isArk(base) && model.startsWith("doubao-seed-2-")) {
            body.put("thinking", JSONObject().put("type", "disabled"))
            body.put("response_format", JSONObject().put("type", "json_object"))
            body.put("max_tokens", 1600)
        }
        return body
    }
    fun image(base: String, model: String, prompt: String): JSONObject {
        val body = JSONObject().put("model", model).put("prompt", prompt)
        if (isArk(base) && model.startsWith("doubao-seedream-")) {
            val size = if (model.startsWith("doubao-seedream-5-0-pro-") || model.startsWith("doubao-seedream-5-0-flash-")) "1K" else "2K"
            body.put("size", size).put("response_format", "url")
            if (model.startsWith("doubao-seedream-5-")) body.put("output_format", "jpeg")
        } else body.put("n", 1).put("size", "1024x1024")
        return body
    }
}

object AiJson {
    private fun text(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("\n") { text(value.opt(it)) }
        is JSONObject -> when {
            value.has("text") -> text(value.opt("text"))
            value.has("content") -> text(value.opt("content"))
            else -> ""
        }
        else -> ""
    }
    fun parse(response: JSONObject): JSONObject {
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        message?.optJSONObject("parsed")?.let { return it }
        if (!(message?.opt("refusal") as? String).isNullOrBlank()) throw ApiFailure("模型拒绝生成该词的资料", "ModelRefusal")
        val raw = text(message?.opt("content")).ifBlank { text(response.opt("output_text")) }
            .ifBlank { text(response.opt("output")) }
        if (raw.isBlank()) throw ApiFailure(if (choice?.optString("finish_reason") == "length")
            "模型输出被截断，请检查输出长度设置或使用普通文本模式" else "模型没有返回正文 JSON，请检查模型输出格式", "EmptyModelContent")
        // Read the first balanced JSON object. Braces inside quoted strings are not delimiters.
        var depth = 0; var start = -1; var quoted = false; var escaped = false
        for ((index, ch) in raw.withIndex()) {
            if (start < 0) { if (ch != '{') continue; start = index; depth = 1; continue }
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when(ch) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) {
                    val parsed = runCatching { JSONObject(raw.substring(start, index + 1)) }.getOrNull()
                    if (parsed != null) return parsed.optJSONObject("data") ?: parsed.optJSONObject("word") ?: parsed
                    start = -1
                } }
            }
        }
        throw ApiFailure("模型没有返回有效的 JSON 词卡，已保留已有资料", "InvalidModelJson")
    }
    fun normalize(raw: JSONObject, word: String): JSONObject {
        val result = JSONObject(raw.toString())
        for (key in result.keys().asSequence().toList()) if (result.opt(key) == JSONObject.NULL) result.remove(key)
        fun alias(target: String, names: List<String>) {
            if (!result.optString(target).isNullOrBlank() && result.opt(target) != JSONObject.NULL) return
            for (name in names) if (raw.has(name) && raw.opt(name) != JSONObject.NULL) {
                result.put(target, raw.opt(name)); break
            }
        }
        alias("meaningZh", listOf("meaning_zh","translation","meaning","中文释义"))
        alias("meaningsZh", listOf("meanings_zh","meanings","definitions"))
        alias("phonetic", listOf("ipa","pronunciation","音标"))
        alias("exampleEn", listOf("example_en","sentence_en","english_sentence","英文例句"))
        alias("exampleZh", listOf("example_zh","sentence_zh","sentenceTranslation","例句翻译"))
        alias("segments", listOf("chunks","syllables","spellingChunks","分块"))
        alias("imageQuery", listOf("image_query"))
        alias("imagePrompt", listOf("image_prompt"))
        raw.optJSONObject("example")?.let {
            if (result.optString("exampleEn").isBlank()) result.put("exampleEn", it.optString("en").ifBlank { it.optString("english") })
            if (result.optString("exampleZh").isBlank()) result.put("exampleZh", it.optString("zh").ifBlank { it.optString("chinese") })
        }
        for (key in listOf("meaningZh", "phonetic", "exampleEn", "exampleZh", "imageQuery", "imagePrompt"))
            if (result.has(key) && result.opt(key) !is String) result.remove(key)
        var chunks = result.optJSONArray("segments")?.let { ContentParsing.strings(it) } ?: result.optString("segments")
            .split(Regex("[·•\\s/]+")).filter(String::isNotBlank)
        if (chunks.size == 1 && !word.contains('-') && chunks[0].contains('-')) chunks = chunks[0].split('-').filter(String::isNotBlank)
        if (chunks.joinToString("").equals(word, true)) {
            var offset = 0
            chunks = chunks.map { val segment = word.substring(offset, offset + it.length); offset += it.length; segment }
            result.put("segments", JSONArray(chunks))
        }
        return result
    }
}
