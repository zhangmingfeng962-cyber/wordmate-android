package com.wordmate.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WordMateTheme { WordMateRoot() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordMateRoot(vm: AppViewModel = viewModel()) {
    val words by vm.words.collectAsStateWithLifecycle()
    val reviews by vm.reviews.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val mixed by vm.mixed.collectAsStateWithLifecycle()
    val queueJobs by vm.enrichmentJobs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val candidates by vm.ocrWords.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var quiz by rememberSaveable { mutableStateOf(false) }
    var addMode by rememberSaveable { mutableStateOf(false) }
    var chooseAdd by remember { mutableStateOf(false) }
    var imageTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { vm.message.collect { snackbar.showSnackbar(it) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshDay()
            if (event == Lifecycle.Event.ON_STOP) vm.repo.speech.stop()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { val id = imageTarget; if (id == null) vm.scan(uri) else vm.image(id, uri) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) captureUri?.let { vm.scan(Uri.parse(it)) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::export) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it?.toString() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.notify("通知权限未开启，打开 App 仍可正常完成每日学习。")
    }
    fun leaveLearning() { vm.repo.speech.stop(); quiz = false; selectedId = null }
    BackHandler(quiz || selectedId != null) { leaveLearning() }
    val selected = words.firstOrNull { it.id == selectedId }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text(if (quiz) "今日抽查" else if (selectedId != null) "单词卡片" else "词伴", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { if (quiz || selectedId != null) IconButton(onClick = { leaveLearning() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = { if (!quiz && selectedId == null) Text("只学你选择的词", style = MaterialTheme.typography.labelMedium, color = InkMuted, modifier = Modifier.padding(end = 20.dp)) })
        },
        bottomBar = {
            if (!quiz && selectedId == null) NavigationBar(containerColor = Cream) {
                listOf("今日学习", "我的词本", "设置").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, label = { Text(label) },
                        icon = { Icon(when(index) { 0 -> Icons.Outlined.WbSunny; 1 -> Icons.AutoMirrored.Outlined.MenuBook; else -> Icons.Outlined.Tune }, label) })
                }
            }
        },
        floatingActionButton = {
            if (!quiz && selectedId == null && tab != 2) FloatingActionButton(onClick = { chooseAdd = true }, containerColor = Teal) {
                Icon(Icons.Outlined.Add, "添加自己的单词", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (busy.isNotEmpty()) Surface(color = PaleTeal) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(busy, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = vm::cancel, contentPadding = PaddingValues(0.dp)) { Text("停止") }
                }
            }
            when {
                quiz -> MixedQuizScreen(vm, mixed, settings, busy.isNotBlank(), onDone = { quiz = false })
                selected != null -> WordDetailScreen(vm, selected, settings,
                    pickImage = { imageTarget = selected.id; gallery.launch(arrayOf("image/*")) },
                    onDeleted = { selectedId = null })
                selectedId != null -> EmptyState("这个词已经移除", "返回词本添加或查看其他单词。", Icons.AutoMirrored.Outlined.MenuBook)
                tab == 0 -> TodayScreen(words, reviews, mixed?.let { state ->
                    state.summary().let { summary ->
                        if (state.finished && MixedRules.additional(state, words, reviews).isNotEmpty()) summary.copy(phase = "meaning") else summary
                    }
                } ?: session, settings, onStart = { vm.start { quiz = true } },
                    onAdd = { chooseAdd = true }, onPending = { tab = 1 }, enabled = busy.isEmpty())
                tab == 1 -> WordbookScreen(words, reviews, onWord = { selectedId = it }, onAdd = { chooseAdd = true },
                    queuePanel = { EnrichmentPanel(queueJobs, settings.enrichmentPaused,
                        onAll = { vm.enrichAll() }, onRetry = { vm.enrichAll(true) },
                        onPause = vm::pauseEnrichment, onResume = vm::resumeEnrichment,
                        accountNotice = settings.aiAccountNotice, onFree = vm::continueFreeEnrichment) })
                else -> SettingsScreen(vm, settings,
                    requestNotification = { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onExport = { export.launch("词伴备份-" + LocalDate.now() + ".zip") },
                    onRestore = { restore.launch(arrayOf("application/zip", "application/octet-stream")) })
            }
        }
    }
    if (chooseAdd) AlertDialog(onDismissRequest = { chooseAdd = false }, title = { Text("添加想记的单词") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("单词由你选择，资料由词伴来整理。")
            FilledTonalButton(onClick = { chooseAdd = false; addMode = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(8.dp)); Text("输入拼写 / 批量粘贴") }
            FilledTonalButton(onClick = { chooseAdd = false; imageTarget = null; gallery.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(8.dp)); Text("从图片识别") }
            FilledTonalButton(onClick = {
                chooseAdd = false
                try {
                    val file = File(context.cacheDir, "photos/capture.jpg").apply { parentFile?.mkdirs() }
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    captureUri = uri.toString(); camera.launch(uri)
                } catch (_: Exception) { vm.notify("暂时无法打开相机，请从相册选择图片。") }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("拍照识别") }
        } }, confirmButton = { TextButton(onClick = { chooseAdd = false }) { Text("取消") } })
    if (addMode) AddWordsDialog(onDismiss = { addMode = false }, onAdd = { vm.add(it) { addMode = false } }, busy = busy.isNotBlank())
    candidates?.let { list -> OcrWordsDialog(list, onDismiss = { vm.ocrWords.value = null },
        onAdd = { vm.add(it) { vm.ocrWords.value = null } }, busy = busy.isNotBlank()) }
    restoreUri?.let { raw -> AlertDialog(onDismissRequest = { restoreUri = null }, title = { Text("恢复备份？") },
        text = { Text("校验成功后，备份中的词本和学习进度将替换当前数据。建议先导出当前备份。已保存的 API 密钥不会被导入或导出。") },
        confirmButton = { TextButton(onClick = { restoreUri = null; vm.restore(Uri.parse(raw)) }) { Text("校验并恢复") } },
        dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("取消") } }) }
}
