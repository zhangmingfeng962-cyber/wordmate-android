package com.wordmate.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

val ExerciseBlue=Color(0xFFCEE4FC)

@Composable
fun MixedQuizScreen(vm: AppViewModel, state: MixedSession?, settings: AppSettings, busy: Boolean, onDone: () -> Unit) {
    if (state == null) {
        EmptyState("正在准备练习", "有中文意思的词就可以先练，句图在后台补充。", Icons.Outlined.AutoStories)
        TextButton(onClick = onDone) { Text("返回") }; return
    }
    if (state.finished) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(20.dp)); Icon(Icons.Outlined.TaskAlt, null, tint = Teal, modifier = Modifier.size(72.dp))
            Text("今天又记住了一点。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("完成 " + state.completed() + " 个词 · 首轮全对 " + state.correct() + " 个")
            Note("图片与词义共同反映意思掌握情况；拼写单独记录。重考不抹掉首次错误，也不重复升级间隔。")
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("回到今日学习") }
        }
        return
    }
    val q = state.current() ?: return
    val card = state.cards[q.wordId] ?: return
    var audioReady by remember(q.id) { mutableStateOf(false) }
    var playing by remember(q.id) { mutableStateOf(false) }
    var error by remember(q.id) { mutableStateOf("") }
    var imageProblem by remember(q.id) { mutableStateOf(false) }
    var typed by rememberSaveable(q.id) { mutableStateOf(state.draft) }
    var useSystem by remember(q.id){mutableStateOf(false)}
    var recording by remember(q.id){mutableStateOf<Pronunciation?>(null)}
    val context = LocalContext.current
    val keyboard=LocalSoftwareKeyboardController.current
    val scroll=rememberScrollState()
    fun play(retry:Boolean=false) {
        playing = true; audioReady = false; error = ""
        vm.repo.speech.speakWord(card.spelling, settings.accent,systemFallback=useSystem,retry=retry) {
            playing = false; audioReady = it.isSuccess; error = it.exceptionOrNull()?.message.orEmpty();recording=it.getOrNull()
        }
    }
    LaunchedEffect(state.groupIndex,settings.accent){
        vm.repo.pronunciation.prefetch(state.groups[state.groupIndex].mapNotNull{state.cards[it.wordId]},settings.accent)
    }
    LaunchedEffect(q.id, state.feedback) {
        scroll.scrollTo(0)
        if (!state.feedback) play() else {
            keyboard?.hide()
            vm.repo.speech.stop()
        }
    }
    DisposableEffect(Unit) { onDispose { vm.repo.speech.stop() } }
    Column(Modifier.fillMaxSize().background(ExerciseBlue)) {
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { if(state.version>=3)state.stageIndex/4f else state.position.toFloat()/state.questions.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text("第 " + (state.groupIndex + 1) + " / " + state.groups.size + " 组 · " +
                state.groups[state.groupIndex].size + " 个词 · " + (state.position + 1) + "/" + state.questions.size +
                if(state.version>=3)" · 第 "+state.round+" 轮" else if (q.retry) " · 再练一次" else "", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            if (state.feedback) {
                val result = state.answers.last { it.questionId == q.id }
                Text(if (result.correct) "记住了！" else "看一遍，再记牢一点。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (q.kind == "spelling" && !result.correct) Note("你的拼写：" + result.value.ifBlank { "未填写" } + "\n" +
                    if (result.assisted) "使用了提示，这次安排加强复习。" else LearningRules.diff(card.spelling, result.value))
                LearningCard(card, vm, settings,exercise=true)
            } else {
                Text(when(q.kind) { "image" -> "① 看词听音，选择对应图片"; "meaning" -> "② 这个单词是什么意思？"; "listening" -> "③ 听声音，选择单词"; else -> "④ 听一听，写出这个词" },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (q.kind in listOf("image","meaning")) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(card.spelling, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Teal)
                        if (card.phonetic.isNotBlank()) Text(card.phonetic, style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    }
                    OutlinedButton(onClick = { play() }) { Icon(Icons.Outlined.Headphones, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("再听") }
                } else FilledTonalButton(onClick = { play() }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Icon(Icons.Outlined.Headphones, null); Spacer(Modifier.width(8.dp)); Text("再听一遍完整单词")
                }
                Text(if (playing) "正在播放…" else if (audioReady) "已播放，可以作答" else "先听一遍，再开始", color = InkMuted, style = MaterialTheme.typography.labelSmall)
                if(audioReady)Text(if(recording!=null)"真人录音 · "+if(settings.accent=="en-US")"美式" else "英式" else "系统合成语音",style=MaterialTheme.typography.labelSmall)
                if (error.isNotBlank()) {
                    Note(error)
                    Row{
                        TextButton(onClick={useSystem=false;play(true)}){Text("重试真人录音")}
                        TextButton(onClick={useSystem=true;play()}){Text("使用系统语音继续")}
                    }
                    TextButton(onClick = {
                        try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    }) { Text("打开系统朗读设置") }
                }
                when(q.kind) {
                    "image" -> {
                        PictureChoices(q.pictures, audioReady && !busy, onUnavailable = { imageProblem = true }) { vm.answerMixed(q.id, it) }
                        if (imageProblem) {
                            Note("图片暂时不可用，可以先完成文字练习，这题不会算错。")
                            TextButton(onClick = { vm.skipBrokenPicture(q.id) }) { Text("跳过图片，继续文字练习") }
                        }
                        Text("通用干扰图：Twemoji · CC BY 4.0，详见设置中的来源说明。", style = MaterialTheme.typography.labelSmall, color = InkMuted)
                    }
                    "meaning","listening" -> TextChoices(q.choices,audioReady&&!busy){vm.answerMixed(q.id,it)}
                    "spelling" -> {
                        OutlinedTextField(typed, { typed = it.take(100); vm.draftMixed(q.id, typed) }, enabled = audioReady && !busy,
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("输入英文拼写") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                        if (state.assisted) Note("首字母：" + card.spelling.first() + "；共 " + card.spelling.length + " 个字符。这次记为需要加强。")
                        Button(onClick = { vm.answerMixed(q.id, typed) }, enabled = audioReady && !busy && typed.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("提交拼写") }
                        TextButton(onClick = { vm.hintMixed(q.id) }, enabled = audioReady && !busy && !state.assisted) { Text("看拼写提示") }
                    }
                }
                TextButton(onClick = { vm.answerMixed(q.id, "__forgot__", true) }, enabled = audioReady && !busy) { Text("不认识 / 想不起来") }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (state.feedback) Button(onClick = { vm.nextMixed(q.id) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(50.dp)) { Text("继续") }
    }
}

@Composable fun TextChoices(choices:List<String>,enabled:Boolean,onChoice:(String)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        choices.chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            row.forEach{choice->OutlinedCard(onClick={onChoice(choice)},enabled=enabled,
                modifier=Modifier.weight(1f).heightIn(min=105.dp),colors=CardDefaults.outlinedCardColors(containerColor=Color.White.copy(alpha=.55f))){
                Box(Modifier.fillMaxWidth().padding(16.dp),contentAlignment=Alignment.Center){Text(choice,style=MaterialTheme.typography.titleMedium)}
            }}
        }}
    }
}

@Composable fun PictureChoices(choices: List<ImageOption>, enabled: Boolean, onUnavailable: () -> Unit = {}, onChoice: (String) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        choices.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { columnIndex, choice ->
                    var loaded by remember(choice.image) { mutableStateOf(false) }
                    OutlinedCard(onClick = { onChoice(choice.id) }, enabled = enabled && loaded,
                        modifier = Modifier.weight(1f).aspectRatio(1.25f).semantics { stateDescription = if (loaded) "图片已加载" else "图片加载中" },
                        colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
                        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                            AsyncImage(model = imageModel(context, choice.image),
                                contentDescription = "图片选项 " + (rowIndex * 2 + columnIndex + 1),
                                onSuccess = { loaded = true }, onError = { onUnavailable() },
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
