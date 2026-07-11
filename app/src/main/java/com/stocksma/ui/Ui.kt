package com.stocksma.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stocksma.data.AppDb
import com.stocksma.data.AppSettings
import com.stocksma.data.CustomConfig
import com.stocksma.data.CustomProvider
import com.stocksma.data.Instrument
import com.stocksma.data.PricePoint
import com.stocksma.data.Providers
import com.stocksma.data.SettingsKeys
import com.stocksma.data.SettingsRepo
import com.stocksma.data.SymbolMatch
import com.stocksma.data.ThemeMode
import com.stocksma.domain.Sma
import com.stocksma.work.FetchWorker
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- Theme ----------

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun StockSmaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

// ---------- ViewModel ----------

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    private val settingsRepo = SettingsRepo(app)

    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val instruments = dao.instruments().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun prices(id: Long) = dao.prices(id)

    fun apiKeyOf(providerId: String): String? = settingsRepo.apiKey(providerId)

    suspend fun search(query: String): Result<List<SymbolMatch>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.value
            val p = Providers.byId(s.providerId, s.custom)
            p.searchSymbol(query, settingsRepo.apiKey(p.id))
        }
    }

    fun add(symbol: String, name: String, window: Int, thresholdPct: Double, seed: Double?) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = dao.insert(
                Instrument(
                    symbol = symbol, name = name, smaWindow = window, thresholdPct = thresholdPct,
                    seedValue = seed, seedEpochDay = if (seed != null) LocalDate.now().toEpochDay() else null
                )
            )
            // Backfill daily history when the provider offers it, so the SMA is available immediately.
            runCatching {
                val s = settings.value
                val p = Providers.byId(s.providerId, s.custom)
                val bars = p.getDailyHistory(symbol, settingsRepo.apiKey(p.id))
                dao.upsertPrices(bars.map { PricePoint(instrumentId = id, epochDay = it.epochDay, close = it.close) })
            }
        }
    }

    fun update(inst: Instrument) { viewModelScope.launch(Dispatchers.IO) { dao.update(inst) } }
    fun delete(inst: Instrument) { viewModelScope.launch(Dispatchers.IO) { dao.delete(inst) } }
    fun refreshNow() = FetchWorker.fetchNow(getApplication())

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { settingsRepo.update { it[SettingsKeys.THEME] = mode.name } } }
    fun setProvider(id: String) { viewModelScope.launch { settingsRepo.update { it[SettingsKeys.PROVIDER] = id } } }
    fun setFetches(n: Int) { viewModelScope.launch { settingsRepo.update { it[SettingsKeys.FETCHES] = n } } }
    fun setCooldown(hours: Int) { viewModelScope.launch { settingsRepo.update { it[SettingsKeys.COOLDOWN] = hours } } }
    fun setApiKey(providerId: String, key: String) = settingsRepo.setApiKey(providerId, key)
    fun setCustom(cfg: CustomConfig) { viewModelScope.launch { settingsRepo.update { it[SettingsKeys.CUSTOM] = SettingsRepo.encodeCustom(cfg) } } }

    suspend fun testCustom(cfg: CustomConfig, sample: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching { CustomProvider(cfg).getDailyHistory(sample, null).size }
    }
}

// ---------- Navigation root (adaptive: list/detail side by side on tablets) ----------

private enum class Nav { Watchlist, Add, Settings }

@Composable
fun AppRoot(vm: AppViewModel, width: WindowWidthSizeClass) {
    var nav by remember { mutableStateOf(Nav.Watchlist) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val instruments by vm.instruments.collectAsStateWithLifecycle()

    when (nav) {
        Nav.Add -> AddScreen(vm, onClose = { nav = Nav.Watchlist })
        Nav.Settings -> SettingsScreen(vm, onClose = { nav = Nav.Watchlist })
        Nav.Watchlist -> {
            val selected = instruments.firstOrNull { it.id == selectedId }
            if (width == WindowWidthSizeClass.Expanded) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.42f)) {
                        WatchlistScreen(vm, instruments, selectedId, { selectedId = it }, { nav = Nav.Add }, { nav = Nav.Settings })
                    }
                    VerticalDivider()
                    Box(Modifier.weight(0.58f)) {
                        if (selected != null) DetailScreen(vm, selected, onBack = null)
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select an instrument") }
                    }
                }
            } else {
                if (selected != null) DetailScreen(vm, selected, onBack = { selectedId = null })
                else WatchlistScreen(vm, instruments, selectedId, { selectedId = it }, { nav = Nav.Add }, { nav = Nav.Settings })
            }
        }
    }
}

// ---------- Watchlist ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    vm: AppViewModel,
    instruments: List<Instrument>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stock SMA") },
                actions = {
                    IconButton(onClick = { vm.refreshNow() }) { Icon(Icons.Filled.Refresh, "Refresh now") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add instrument") } }
    ) { padding ->
        if (instruments.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No instruments yet.\nTap + to add a stock or ETF.")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(instruments, key = { it.id }) { inst ->
                    InstrumentRow(vm, inst, inst.id == selectedId) { onSelect(inst.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun InstrumentRow(vm: AppViewModel, inst: Instrument, selected: Boolean, onClick: () -> Unit) {
    val prices by remember(inst.id) { vm.prices(inst.id) }.collectAsStateWithLifecycle(emptyList())
    val last = prices.lastOrNull()
    val sma = Sma.compute(FetchWorker.closesForSma(prices, inst), inst.smaWindow, inst.seedValue)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(inst.symbol.uppercase(), style = MaterialTheme.typography.titleMedium)
            Text(inst.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (inst.lastFetchError != null) {
                Text(
                    "Fetch error: ${inst.lastFetchError}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(last?.let { String.format(Locale.US, "%.2f", it.close) } ?: "\u2013", style = MaterialTheme.typography.titleMedium)
            if (sma != null && last != null) {
                val dev = Sma.deviationPct(last.close, sma)
                Text(
                    String.format(Locale.US, "SMA %.2f (%+.1f%%)", sma, dev),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dev >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ---------- Detail (chart + per-instrument configuration) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: AppViewModel, inst: Instrument, onBack: (() -> Unit)?) {
    if (onBack != null) BackHandler { onBack() }
    val prices by remember(inst.id) { vm.prices(inst.id) }.collectAsStateWithLifecycle(emptyList())
    var windowText by remember(inst.id) { mutableStateOf(inst.smaWindow.toString()) }
    var thresholdText by remember(inst.id) { mutableStateOf(inst.thresholdPct.toString()) }
    var seedText by remember(inst.id) { mutableStateOf(inst.seedValue?.toString() ?: "") }
    var horizon by remember(inst.id) { mutableStateOf(maxOf(inst.smaWindow, 90)) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(inst.symbol.uppercase()) },
            navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            },
            actions = {
                IconButton(onClick = { vm.delete(inst); onBack?.invoke() }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(inst.name, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "1M", 90 to "3M", 180 to "6M", 365 to "1Y").forEach { (days, label) ->
                    FilterChip(selected = horizon == days, onClick = { horizon = days }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            PriceChart(prices, inst.smaWindow, inst.seedValue, inst.seedEpochDay, horizon)
            Spacer(Modifier.height(8.dp))

            val realSinceSeed = prices.count { p -> inst.seedEpochDay?.let { p.epochDay >= it } ?: true }
            val seeded = inst.seedValue != null && realSinceSeed < inst.smaWindow
            Text(
                if (seeded) "SMA is seed-based ($realSinceSeed/${inst.smaWindow} real days collected)"
                else "SMA is fully based on collected data",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(windowText, { windowText = it }, label = { Text("SMA window (days)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(thresholdText, { thresholdText = it }, label = { Text("Notification threshold (%)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(seedText, { seedText = it }, label = { Text("SMA seed value (optional)") }, singleLine = true)
            Text(
                "The seed acts as the starting point of the average until the window is filled with real data.",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val seed = seedText.toDoubleOrNull()
                vm.update(
                    inst.copy(
                        smaWindow = windowText.toIntOrNull()?.coerceIn(2, 400) ?: inst.smaWindow,
                        thresholdPct = thresholdText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: inst.thresholdPct,
                        seedValue = seed,
                        seedEpochDay = if (seed != null) (inst.seedEpochDay ?: LocalDate.now().toEpochDay()) else null,
                        lastNotifiedSide = null
                    )
                )
            }) { Text("Save") }

            if (inst.lastFetchError != null) {
                Spacer(Modifier.height(8.dp))
                Text("Last fetch error: ${inst.lastFetchError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------- Chart (Canvas line chart: price + SMA overlay, theme-aware colors) ----------

@Composable
fun PriceChart(
    points: List<PricePoint>,
    window: Int,
    seed: Double?,
    seedDay: Long?,
    horizonDays: Int,
    modifier: Modifier = Modifier
) {
    val sorted = remember(points) { points.sortedBy { it.epochDay } }
    val smaSeries = remember(sorted, window, seed, seedDay) {
        sorted.indices.mapNotNull { i ->
            val upTo = sorted.subList(0, i + 1)
            val usable = if (seed != null && seedDay != null) upTo.filter { it.epochDay >= seedDay } else upTo
            Sma.compute(usable.map { it.close }, window, seed)?.let { sorted[i].epochDay to it }
        }
    }
    val maxDay = sorted.lastOrNull()?.epochDay
    if (maxDay == null) {
        Text("No price data yet.", modifier = modifier)
        return
    }
    val minDay = maxDay - horizonDays
    val shownPrices = sorted.filter { it.epochDay >= minDay }
    val shownSma = smaSeries.filter { it.first >= minDay }
    if (shownPrices.size < 2) {
        Text("Not enough data for a chart yet.", modifier = modifier)
        return
    }
    val values = shownPrices.map { it.close } + shownSma.map { it.second }
    val minV = values.min()
    val maxV = values.max()
    val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
    val priceColor = MaterialTheme.colorScheme.primary
    val smaColor = MaterialTheme.colorScheme.tertiary

    Column(modifier.fillMaxWidth()) {
        Text(String.format(Locale.US, "high %.2f", maxV), style = MaterialTheme.typography.labelSmall)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            fun x(day: Long): Float =
                (day - minDay).toFloat() / (maxDay - minDay).toFloat().coerceAtLeast(1f) * size.width
            fun y(v: Double): Float = size.height - ((v - minV) / span * size.height).toFloat()

            val pricePath = Path()
            shownPrices.forEachIndexed { i, p ->
                if (i == 0) pricePath.moveTo(x(p.epochDay), y(p.close)) else pricePath.lineTo(x(p.epochDay), y(p.close))
            }
            drawPath(pricePath, priceColor, style = Stroke(width = 4f))

            if (shownSma.size >= 2) {
                val smaPath = Path()
                shownSma.forEachIndexed { i, pair ->
                    if (i == 0) smaPath.moveTo(x(pair.first), y(pair.second)) else smaPath.lineTo(x(pair.first), y(pair.second))
                }
                drawPath(smaPath, smaColor, style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
            }
        }
        Text(String.format(Locale.US, "low %.2f", minV), style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(priceColor))
            Text("  Price    ", style = MaterialTheme.typography.labelSmall)
            Box(Modifier.size(10.dp).background(smaColor))
            Text("  SMA($window)", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------- Add instrument ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(vm: AppViewModel, onClose: () -> Unit) {
    BackHandler { onClose() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SymbolMatch>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SymbolMatch?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Add instrument") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Ticker or name") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(enabled = query.isNotBlank() && !searching, onClick = {
                    searching = true; error = null
                    scope.launch {
                        vm.search(query).onSuccess { results = it }.onFailure { error = it.message }
                        searching = false
                    }
                }) { Text(if (searching) "\u2026" else "Search") }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(results) { m ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { pending = m }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(m.symbol.uppercase(), style = MaterialTheme.typography.titleSmall)
                        Text(m.name, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    pending?.let { match ->
        AddDialog(
            match,
            onDismiss = { pending = null },
            onConfirm = { window, threshold, seed ->
                vm.add(match.symbol, match.name, window, threshold, seed)
                pending = null
                onClose()
            }
        )
    }
}

@Composable
private fun AddDialog(match: SymbolMatch, onDismiss: () -> Unit, onConfirm: (Int, Double, Double?) -> Unit) {
    var windowText by remember { mutableStateOf("30") }
    var thresholdText by remember { mutableStateOf("0") }
    var seedText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${match.symbol.uppercase()}") },
        text = {
            Column {
                OutlinedTextField(windowText, { windowText = it }, label = { Text("SMA window (days)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(thresholdText, { thresholdText = it }, label = { Text("Threshold (%)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(seedText, { seedText = it }, label = { Text("Current SMA seed (optional)") }, singleLine = true)
                Spacer(Modifier.height(4.dp))
                Text(
                    "If your data source provides no history, enter today's average as a starting point so you don't have to wait a full window.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    windowText.toIntOrNull()?.coerceIn(2, 400) ?: 30,
                    thresholdText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                    seedText.toDoubleOrNull()
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------- Settings ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onClose: () -> Unit) {
    BackHandler { onClose() }
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(settings.themeMode == ThemeMode.SYSTEM, { vm.setTheme(ThemeMode.SYSTEM) }, { Text("Follow system") })
                FilterChip(settings.themeMode == ThemeMode.LIGHT, { vm.setTheme(ThemeMode.LIGHT) }, { Text("Light") })
                FilterChip(settings.themeMode == ThemeMode.DARK, { vm.setTheme(ThemeMode.DARK) }, { Text("Dark") })
            }

            Spacer(Modifier.height(16.dp))
            Text("Data source", style = MaterialTheme.typography.titleMedium)
            (Providers.builtIn().map { it.id to it.displayName } + ("custom" to "Custom source")).forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setProvider(id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = settings.providerId == id, onClick = { vm.setProvider(id) })
                    Text(label)
                }
            }
            Text(
                "Switching the source keeps all price history already stored on the device.",
                style = MaterialTheme.typography.labelSmall
            )

            Providers.builtIn().filter { it.requiresKey }.forEach { p ->
                var keyText by remember(p.id) { mutableStateOf(vm.apiKeyOf(p.id) ?: "") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    keyText, { keyText = it },
                    label = { Text("${p.displayName} \u2014 API key") }, singleLine = true,
                    trailingIcon = { TextButton(onClick = { vm.setApiKey(p.id, keyText) }) { Text("Save") } }
                )
                p.signupUrl?.let { Text("Free key: $it", style = MaterialTheme.typography.labelSmall) }
            }

            Spacer(Modifier.height(16.dp))
            Text("Background fetches per day", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 4, 8, 24).forEach { n ->
                    FilterChip(settings.fetchesPerDay == n, { vm.setFetches(n) }, { Text("$n\u00d7") })
                }
            }
            Text(
                "Android enforces a 15-minute minimum for periodic work; Doze mode and battery optimization may delay fetches.",
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.height(16.dp))
            Text("Notification cooldown (hours)", style = MaterialTheme.typography.titleMedium)
            var cooldownText by remember { mutableStateOf(settings.cooldownHours.toString()) }
            OutlinedTextField(
                cooldownText,
                { v -> cooldownText = v; v.toIntOrNull()?.let { vm.setCooldown(it.coerceIn(0, 168)) } },
                label = { Text("0 = re-arm only when the price crosses back") },
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))
            Text("Custom data source", style = MaterialTheme.typography.titleMedium)
            CustomSourceEditor(vm, settings.custom)
        }
    }
}

@Composable
private fun CustomSourceEditor(vm: AppViewModel, initial: CustomConfig) {
    val scope = rememberCoroutineScope()
    var cfg by remember(initial) { mutableStateOf(initial) }
    var sample by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column {
        OutlinedTextField(cfg.name, { cfg = cfg.copy(name = it) }, label = { Text("Name") }, singleLine = true)
        OutlinedTextField(cfg.historyUrl, { cfg = cfg.copy(historyUrl = it) }, label = { Text("History URL template ({symbol}, {apikey})") })
        OutlinedTextField(cfg.apiKey, { cfg = cfg.copy(apiKey = it) }, label = { Text("API key (optional)") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(cfg.format.equals("CSV", true), { cfg = cfg.copy(format = "CSV") }, { Text("CSV") })
            FilterChip(cfg.format.equals("JSON", true), { cfg = cfg.copy(format = "JSON") }, { Text("JSON") })
        }
        if (cfg.format.equals("CSV", true)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    cfg.csvDateCol.toString(), { v -> v.toIntOrNull()?.let { cfg = cfg.copy(csvDateCol = it) } },
                    Modifier.weight(1f), label = { Text("Date column") }, singleLine = true
                )
                OutlinedTextField(
                    cfg.csvCloseCol.toString(), { v -> v.toIntOrNull()?.let { cfg = cfg.copy(csvCloseCol = it) } },
                    Modifier.weight(1f), label = { Text("Close column") }, singleLine = true
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(cfg.csvSkipHeader, { cfg = cfg.copy(csvSkipHeader = it) })
                Text("Skip header row")
            }
        } else {
            OutlinedTextField(cfg.jsonArrayPath, { cfg = cfg.copy(jsonArrayPath = it) }, label = { Text("Array path (dot-separated, empty = root array)") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cfg.jsonDateField, { cfg = cfg.copy(jsonDateField = it) }, Modifier.weight(1f), label = { Text("Date field") }, singleLine = true)
                OutlinedTextField(cfg.jsonCloseField, { cfg = cfg.copy(jsonCloseField = it) }, Modifier.weight(1f), label = { Text("Close field") }, singleLine = true)
            }
        }
        OutlinedTextField(cfg.dateFormat, { cfg = cfg.copy(dateFormat = it) }, label = { Text("Date format (e.g. yyyy-MM-dd)") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(sample, { sample = it }, Modifier.weight(1f), label = { Text("Test symbol") }, singleLine = true)
            Button(enabled = sample.isNotBlank() && cfg.historyUrl.isNotBlank(), onClick = {
                scope.launch {
                    testResult = vm.testCustom(cfg, sample).fold(
                        { "OK \u2014 $it data points parsed" },
                        { "Failed: ${it.message}" }
                    )
                }
            }) { Text("Test") }
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.setCustom(cfg) }) { Text("Save custom source") }
    }
}
