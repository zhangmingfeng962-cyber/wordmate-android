package com.wordmate.app

import androidx.room.Entity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import java.io.File

@Serializable
@Entity(tableName="pronunciations",primaryKeys=["wordId","accent"])
data class Pronunciation(val wordId:String,val accent:String,val file:String,val sourceUrl:String,
    val author:String,val license:String,val licenseUrl:String,val description:String)

object RecordingRules {
    fun allowedLicense(url:String)=url.startsWith("https://creativecommons.org/licenses/by/") ||
        url.startsWith("https://creativecommons.org/licenses/by-sa/") || url.startsWith("https://creativecommons.org/publicdomain/")
    fun candidate(word:String,entryWord:String,audio:String,accent:String):Boolean {
        val url=audio.toHttpUrlOrNull()?:return false
        val suffix=if(accent=="en-US")"-us.mp3" else if(accent=="en-GB")"-uk.mp3" else return false
        return wordKey(word)==wordKey(entryWord)&&url.scheme=="https"&&url.host=="api.dictionaryapi.dev"&&
            url.pathSegments.last().equals(wordKey(word)+suffix,true)
    }
    fun verified(word:String,description:String,author:String,licenseUrl:String):Boolean =
        author.isNotBlank()&&allowedLicense(licenseUrl)&&ContentParsing.hasWord(description,word)&&
            Regex("(?i)pronunciation|recorded").containsMatchIn(description)&&
            !Regex("(?i)text.to.speech|synthe[st]|espeak|\\btts\\b").containsMatchIn(description)
}

class PronunciationService(private val repo:WordRepository) {
    private val lock=Mutex()
    private val misses=mutableMapOf<String,Pair<Long,String>>()
    suspend fun cached(word:String,accent:String):Pronunciation?=repo.dao.pronunciation(wordKey(word),accent)?.takeIf{
        File(repo.app.filesDir,it.file).let{f->f.isFile&&f.length()>44}
    }
    suspend fun resolve(word:String,accent:String,retry:Boolean=false):Pronunciation = withContext(Dispatchers.IO){lock.withLock{
        cached(word,accent)?.let{return@withLock it}
        val key=StageRules.key(wordKey(word),accent)
        val previous=misses[key]
        if(!retry&&previous!=null&&System.currentTimeMillis()-previous.first<600000)error(previous.second)
        try {
            withTimeout(20000){
                val http=repo.content.transport
                val entries=JSONArray(String(http.bytes(http.get("https://api.dictionaryapi.dev/api/v2/entries/en/"+java.net.URLEncoder.encode(word,"UTF-8")),timeoutMs=6000),Charsets.UTF_8))
                for(i in 0 until entries.length()){
                    val entry=entries.optJSONObject(i)?:continue
                    val phonetics=entry.optJSONArray("phonetics")?:continue
                    for(j in 0 until phonetics.length()){
                        val candidate=phonetics.optJSONObject(j)?:continue
                        val audio=candidate.optString("audio")
                        if(!RecordingRules.candidate(word,entry.optString("word"),audio,accent))continue
                        val source=candidate.optString("sourceUrl").toHttpUrlOrNull()?:continue
                        if(source.host!="commons.wikimedia.org"||source.scheme!="https")continue
                        val pageId=source.queryParameter("curid")?.toLongOrNull()?:continue
                        val url="https://commons.wikimedia.org/w/api.php".toHttpUrlOrNull()!!.newBuilder()
                            .addQueryParameter("action","query").addQueryParameter("format","json").addQueryParameter("formatversion","2")
                            .addQueryParameter("pageids",pageId.toString()).addQueryParameter("prop","imageinfo")
                            .addQueryParameter("iiprop","url|extmetadata|mime").build()
                        val info=http.json(http.get(url.toString()),timeoutMs=6000).optJSONObject("query")?.optJSONArray("pages")
                            ?.optJSONObject(0)?.optJSONArray("imageinfo")?.optJSONObject(0)?:continue
                        val meta=info.optJSONObject("extmetadata")?:continue
                        fun value(key:String)=android.text.Html.fromHtml(meta.optJSONObject(key)?.optString("value").orEmpty(),0).toString().trim()
                        val author=value("Artist")
                        val original=info.optString("url").toHttpUrlOrNull()?:continue
                        if(original.scheme!="https"||original.host!="upload.wikimedia.org")continue
                        val description=value("ImageDescription")+" · "+original.pathSegments.last()
                        val licenseUrl=value("LicenseUrl").replace("http://creativecommons.org/","https://creativecommons.org/")
                        if(!RecordingRules.verified(word,description,author,licenseUrl))continue
                        val extension=when(info.optString("mime")){"application/ogg","audio/ogg"->"ogg";"audio/mpeg"->"mp3";"audio/wav","audio/x-wav"->"wav";else->continue}
                        val data=http.bytes(http.get(original.toString()),limit=2*1024*1024,timeoutMs=8000)
                        check(data.size>44){"录音内容为空"}
                        val path="audio/"+digest(("human-v1|"+wordKey(word)+"|"+accent+"|"+original).toByteArray())+"."+extension
                        val file=File(repo.app.filesDir,path).apply{parentFile!!.mkdirs()}
                        val temp=File(file.path+".part")
                        temp.writeBytes(data);check(temp.renameTo(file)){"录音保存失败"}
                        val record=Pronunciation(wordKey(word),accent,path,info.optString("descriptionurl").ifBlank{source.toString()},author,
                            value("LicenseShortName"),licenseUrl,description)
                        if(repo.dao.word(record.wordId)!=null)repo.dao.putPronunciation(record)
                        misses.remove(key)
                        return@withTimeout record
                    }
                }
                error("暂无该拼写和口音的可核实真人录音，可重试或使用系统语音继续。")
            }
        } catch(e:TimeoutCancellationException){throw IllegalStateException("真人录音获取超时，可重试或使用系统语音继续。")}
        catch(e:CancellationException){throw e}
        catch(e:Exception){
            val message=if(e is ApiFailure)"真人录音暂时获取失败，请检查网络后重试，或使用系统语音继续。" else e.message.orEmpty()
            misses[key]=System.currentTimeMillis() to message
            throw IllegalStateException(message)
        }
    }}
    suspend fun prefetch(words:List<WordCard>,accent:String){
        for(word in words.take(5))try{resolve(word.spelling,accent)}catch(e:CancellationException){throw e}catch(_:Exception){}
    }
}
