package app.linger.android

import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import kotlin.math.abs

private data class MediaItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateAdded: Long,
    val isVideo: Boolean
)

private enum class Tab { REVIEW, STATS, FAVORITES, SETTINGS }
private enum class FilterMode { MIXED, PHOTOS, VIDEOS, RECENT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LingerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LingerApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("linger_stats", 0) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(Tab.REVIEW) }
    var allMedia by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var queue by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var batchSize by remember { mutableIntStateOf(prefs.getInt("batch_size", 15)) }
    var filter by remember { mutableStateOf(FilterMode.MIXED) }
    var pendingDelete by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) }
    var seen by remember { mutableStateOf<Set<String>>(prefs.getStringSet("seen", emptySet()) ?: emptySet()) }
    var totalViewed by remember { mutableIntStateOf(prefs.getInt("viewed", 0)) }
    var totalDeleted by remember { mutableIntStateOf(prefs.getInt("deleted", 0)) }
    var freedBytes by remember { mutableLongStateOf(prefs.getLong("freed", 0L)) }
    var permissionGranted by remember { mutableStateOf(hasMediaPermission(context)) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var snackbar by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    fun saveStats() {
        prefs.edit()
            .putInt("viewed", totalViewed)
            .putInt("deleted", totalDeleted)
            .putLong("freed", freedBytes)
            .putStringSet("favorites", favorites)
            .putStringSet("seen", seen)
            .putInt("batch_size", batchSize)
            .apply()
    }

    suspend fun reloadMedia() {
        isLoading = true
        val loaded = withContext(Dispatchers.IO) { queryMedia(context) }
        allMedia = loaded
        isLoading = false
    }

    fun startBatch() {
        val candidates = when (filter) {
            FilterMode.PHOTOS -> allMedia.filter { !it.isVideo }
            FilterMode.VIDEOS -> allMedia.filter { it.isVideo }
            FilterMode.RECENT -> allMedia.filter { System.currentTimeMillis()/1000 - it.dateAdded < 30L*24*3600 }
            FilterMode.MIXED -> allMedia
        }.filter { it.uri.toString() !in seen && it.uri.toString() !in pendingDelete.map { p -> p.uri.toString() } }
        queue = candidates.shuffled().take(batchSize)
        index = 0
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGranted = result.values.any { it }
        if (permissionGranted) scope.launch { reloadMedia(); startBatch() }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val count = pendingDelete.size
            val bytes = pendingDelete.sumOf { it.size }
            totalDeleted += count
            freedBytes += bytes
            val deletedUris = pendingDelete.map { it.uri.toString() }.toSet()
            allMedia = allMedia.filterNot { it.uri.toString() in deletedUris }
            seen = seen - deletedUris
            snackbar = "已删除 $count 项"
        } else {
            snackbar = "已取消删除，照片仍保留"
        }
        pendingDelete = emptyList()
        saveStats()
        startBatch()
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            reloadMedia()
            startBatch()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFD7F36A),
            background = Color(0xFF10110F),
            surface = Color(0xFF1A1C19),
            onBackground = Color(0xFFF3F3EE),
            onSurface = Color(0xFFF3F3EE)
        )
    ) {
        Scaffold(
            containerColor = Color(0xFF10110F),
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF171916), contentColor = Color.White) {
                    NavigationBarItem(selected = tab == Tab.REVIEW, onClick = { tab = Tab.REVIEW }, icon = { Icon(Icons.Rounded.Style, null) }, label = { Text("回顾") })
                    NavigationBarItem(selected = tab == Tab.STATS, onClick = { tab = Tab.STATS }, icon = { Icon(Icons.Rounded.Insights, null) }, label = { Text("统计") })
                    NavigationBarItem(selected = tab == Tab.FAVORITES, onClick = { tab = Tab.FAVORITES }, icon = { Icon(Icons.Rounded.Favorite, null) }, label = { Text("收藏") })
                    NavigationBarItem(selected = tab == Tab.SETTINGS, onClick = { tab = Tab.SETTINGS }, icon = { Icon(Icons.Rounded.Tune, null) }, label = { Text("设置") })
                }
            },
            snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                when (tab) {
                    Tab.REVIEW -> ReviewScreen(
                        permissionGranted, allMedia, queue, index, pendingDelete, filter, isLoading, snackbar,
                        onRequestPermission = {
                            permissionLauncher.launch(
                                if (Build.VERSION.SDK_INT >= 33) {
                                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                                } else {
                                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                            )
                        },
                        onFilter = { filter = it; startBatch() },
                        onAction = { action ->
                            if (queue.isNotEmpty() && index < queue.size) {
                                val item = queue[index]
                                seen = seen + item.uri.toString()
                                totalViewed++
                                when (action) {
                                    "delete" -> pendingDelete = pendingDelete + item
                                    "favorite" -> favorites = favorites + item.uri.toString()
                                }
                                index++
                                saveStats()
                            }
                        },
                        onNextBatch = { startBatch() },
                        onConfirmDelete = { showDeleteDialog = true },
                        onClearMarked = { pendingDelete = emptyList(); startBatch() }
                    )
                    Tab.STATS -> StatsScreen(totalViewed, totalDeleted, freedBytes, allMedia.size, favorites.size)
                    Tab.FAVORITES -> FavoritesScreen(allMedia.filter { it.uri.toString() in favorites }, onRemove = { item ->
                        favorites = favorites - item.uri.toString(); saveStats()
                    })
                    Tab.SETTINGS -> SettingsScreen(batchSize, onBatchSize = { batchSize = it; saveStats(); startBatch() }, onRescan = { scope.launch { reloadMedia(); startBatch() } }, mediaCount = allMedia.size)
                }
            }
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("确认删除？") },
                text = { Text("将从系统相册删除标记的 ${pendingDelete.size} 项。Android 会再次弹出系统确认窗口。删除后的内容通常会进入系统“最近删除”，具体取决于设备相册实现。") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        if (Build.VERSION.SDK_INT >= 30 && pendingDelete.isNotEmpty()) {
                            try {
                                val request = MediaStore.createDeleteRequest(context.contentResolver, pendingDelete.map { it.uri })
                                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                            } catch (_: Exception) { snackbar = "无法发起系统删除请求" }
                        }
                    }) { Text("继续") }
                },
                dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("再想想") } }
            )
        }
    }
}

@Composable
private fun ReviewScreen(
    permission: Boolean, media: List<MediaItem>, queue: List<MediaItem>, index: Int,
    marked: List<MediaItem>, filter: FilterMode, loading: Boolean, message: String,
    onRequestPermission: () -> Unit, onFilter: (FilterMode) -> Unit,
    onAction: (String) -> Unit, onNextBatch: () -> Unit,
    onConfirmDelete: () -> Unit, onClearMarked: () -> Unit
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LINGER", color = Color(0xFFD7F36A), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("把回忆，慢慢去留。", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("${marked.size} 待删除", color = Color(0xFFFFB4A9), fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selected = filter == FilterMode.MIXED, onClick = { onFilter(FilterMode.MIXED) }, label = { Text("混合") })
            FilterChip(selected = filter == FilterMode.PHOTOS, onClick = { onFilter(FilterMode.PHOTOS) }, label = { Text("照片") })
            FilterChip(selected = filter == FilterMode.VIDEOS, onClick = { onFilter(FilterMode.VIDEOS) }, label = { Text("视频") })
            FilterChip(selected = filter == FilterMode.RECENT, onClick = { onFilter(FilterMode.RECENT) }, label = { Text("近30天") })
        }
        Spacer(Modifier.height(14.dp))

        when {
            !permission -> EmptyState("先允许访问照片", "照片只在本机读取，不会上传。", "授予权限", onRequestPermission)
            loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFD7F36A)) }
            media.isEmpty() -> EmptyState("还没有找到媒体", "请检查系统相册中是否有照片或视频。", "重新扫描", onNextBatch)
            queue.isEmpty() || index >= queue.size -> {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFFD7F36A), modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("这一组看完了", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("刚刚完成了 ${queue.size} 张回顾", color = Color.LightGray)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onNextBatch) { Text("再来一组") }
                    }
                }
            }
            else -> {
                Text("随机回顾 · ${index + 1} / ${queue.size}", color = Color.LightGray, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                val item = queue[index]
                var dragX by remember(item.uri) { mutableFloatStateOf(0f) }
                var dragY by remember(item.uri) { mutableFloatStateOf(0f) }
                val rotation by animateFloatAsState(targetValue = dragX / 35f, label = "rotation")
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .pointerInput(item.uri) {
                            detectDragGestures(
                                onDragEnd = {
                                    when {
                                        dragY < -100 -> onAction("delete")
                                        dragY > 100 -> onAction("favorite")
                                        dragX < -100 -> onAction("skip")
                                        dragX > 100 -> onAction("keep")
                                    }
                                    dragX = 0f; dragY = 0f
                                }
                            ) { change, amount ->
                                change.consume()
                                dragX += amount.x
                                dragY += amount.y
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.96f),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF242722))
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(model = item.uri, contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            if (item.isVideo) {
                                Surface(Modifier.align(Alignment.TopStart).padding(14.dp), color = Color.Black.copy(alpha = .65f), shape = CircleShape) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Text("视频", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                            Text(item.name, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .55f)).padding(12.dp), color = Color.White, maxLines = 1)
                        }
                    }
                    if (abs(dragX) > 35 || abs(dragY) > 35) {
                        val label = when { dragY < -35 -> "标记删除"; dragY > 35 -> "收藏"; dragX < -35 -> "跳过"; else -> "保留" }
                        Surface(Modifier.align(Alignment.TopCenter).padding(top = 20.dp), color = Color(0xFFD7F36A), shape = CircleShape) {
                            Text(label, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(Icons.Rounded.DeleteOutline, "删除", Color(0xFFFF8D80)) { onAction("delete") }
                    ActionButton(Icons.Rounded.SkipNext, "跳过", Color.White) { onAction("skip") }
                    ActionButton(Icons.Rounded.FavoriteBorder, "收藏", Color(0xFFD7F36A)) { onAction("favorite") }
                    ActionButton(Icons.Rounded.Check, "保留", Color.White) { onAction("keep") }
                }
            }
        }
        if (marked.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已标记 ${marked.size} 项", Modifier.weight(1f), color = Color(0xFFFFB4A9))
                TextButton(onClick = onClearMarked) { Text("撤销标记") }
                Button(onClick = onConfirmDelete) { Text("批量删除") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF292D27), contentColor = tint), modifier = Modifier.size(54.dp)) {
            Icon(icon, null, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = Color.LightGray)
    }
}

@Composable
private fun EmptyState(title: String, detail: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.PhotoLibrary, null, tint = Color(0xFFD7F36A), modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(detail, color = Color.LightGray)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun StatsScreen(viewed: Int, deleted: Int, freed: Long, library: Int, favorites: Int) {
    Column(Modifier.fillMaxSize().padding(top = 26.dp)) {
        Text("你的回顾", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("每一次去留，都是一点点整理。", color = Color.LightGray)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("浏览过", viewed.toString(), Modifier.weight(1f))
            StatCard("已删除", deleted.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("释放空间", formatBytes(freed), Modifier.weight(1f))
            StatCard("已收藏", favorites.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Surface(color = Color(0xFF1A1C19), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("当前媒体库", color = Color.LightGray)
                Text("$library 项", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("提示：释放空间按删除前读取到的文件大小估算。", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xFF1A1C19), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, color = Color.LightGray, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD7F36A))
        }
    }
}

@Composable
private fun FavoritesScreen(items: List<MediaItem>, onRemove: (MediaItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Text("我的收藏", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("${items.size} 项 · 收藏记录保存在本机", color = Color.LightGray)
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("还没有收藏照片", color = Color.LightGray) }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items.size) { i ->
                    val item = items[i]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A1C19)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(item.uri, null, Modifier.size(66.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(item.name, maxLines = 1, fontWeight = FontWeight.Medium)
                            Text(formatBytes(item.size), color = Color.Gray, fontSize = 12.sp)
                        }
                        IconButton(onClick = { onRemove(item) }) { Icon(Icons.Rounded.Favorite, null, tint = Color(0xFFD7F36A)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(batchSize: Int, onBatchSize: (Int) -> Unit, onRescan: () -> Unit, mediaCount: Int) {
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))
        Text("每组回顾数量", fontWeight = FontWeight.SemiBold)
        Text("$batchSize 张", color = Color(0xFFD7F36A), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Slider(value = batchSize.toFloat(), onValueChange = { onBatchSize(it.toInt()) }, valueRange = 5f..100f, steps = 18)
        Spacer(Modifier.height(12.dp))
        Surface(color = Color(0xFF1A1C19), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("本地媒体库", fontWeight = FontWeight.SemiBold)
                Text("当前扫描到 $mediaCount 项", color = Color.LightGray, fontSize = 13.sp)
                TextButton(onClick = onRescan) { Text("重新扫描") }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("隐私与说明", fontWeight = FontWeight.SemiBold)
        Text("Linger 不会上传照片或视频。媒体读取、收藏记录和浏览统计均保存在本机。删除操作由 Android 系统确认。", color = Color.LightGray, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text("版本 1.0.0 · Android 10+（建议 Android 11 及以上）", color = Color.Gray, fontSize = 12.sp)
    }
}

private fun hasMediaPermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

private fun queryMedia(context: android.content.Context): List<MediaItem> {
    val resolver = context.contentResolver
    val result = mutableListOf<MediaItem>()
    val collections = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true
    )
    for ((collection, video) in collections) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        try {
            resolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result += MediaItem(
                        ContentUris.withAppendedId(collection, id),
                        cursor.getString(nameCol) ?: if (video) "视频" else "照片",
                        cursor.getLong(sizeCol),
                        cursor.getLong(dateCol),
                        video
                    )
                }
            }
        } catch (_: SecurityException) { }
    }
    return result
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${DecimalFormat("#,##0.0").format(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${DecimalFormat("#,##0.0").format(mb)} MB"
    return "${DecimalFormat("#,##0.00").format(mb / 1024.0)} GB"
}
