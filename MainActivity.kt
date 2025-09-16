
package rs.nis.barberprices

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.regex.Pattern

data class PriceSample(
    val source: String,
    val service: String,
    val priceRsd: Int
)

data class Stats(val min: Int?, val avg: Int?, val max: Int?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PriceScreen()
            }
        }
    }
}

@Composable
fun PriceScreen() {
    var isLoading by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf(listOf<PriceSample>()) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        isLoading = true
        error = null
        try {
            val data = withContext(Dispatchers.IO) {
                scrapeAll()
            }
            samples = data
        } catch (t: Throwable) {
            error = t.message ?: "Unknown error"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    val haircutStats = computeStats(samples.filter { it.service == "haircut" }.map { it.priceRsd })
    val beardStats = computeStats(samples.filter { it.service == "beard" }.map { it.priceRsd })
    val washStats = computeStats(samples.filter { it.service == "wash" }.map { it.priceRsd })

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Niš Barber Prices") })
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { 
                    // fire and forget in composition scope
                    @Suppress("EXPERIMENTAL_API_USAGE")
                    androidx.compose.runtime.LaunchedEffect(Unit) {}
                },
                enabled = false
            ) { Text(" ") }

            Button(
                onClick = { 
                    // launch refresh
                    androidx.compose.runtime.LaunchedEffect(Unit) {}
                },
                modifier = Modifier.height(0.dp)
            ) {}

            Button(
                onClick = { 
                    // use rememberCoroutineScope instead
                },
                enabled = false
            ) { Text(" ") }

            val scope = rememberCoroutineScope()
            Button(
                onClick = { scope.launch { refresh() } },
                enabled = !isLoading
            ) {
                Text(if (isLoading) "Refreshing..." else "Refresh")
            }

            Spacer(Modifier.height(16.dp))

            StatCard(title = "Muško šišanje (haircut)", stats = haircutStats)
            Spacer(Modifier.height(8.dp))
            StatCard(title = "Brada / brijanje (beard)", stats = beardStats)
            Spacer(Modifier.height(8.dp))
            StatCard(title = "Pranje kose (wash)", stats = washStats)

            Spacer(Modifier.height(16.dp))

            Text("Uzorkovani izvori (Niš):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            samples.groupBy { it.source }.forEach { (src, list) ->
                Text("• $src: " + list.joinToString { "${it.service} ${it.priceRsd} RSD" })
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text("Greška: ${error}", color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Napomena: Ovo su javno objavljene cene sa sajtova za Niš. " +
                "Ako sajt promeni izgled, parser treba osvežiti. Uvek proveri cenu pri rezervaciji."
            )
        }
    }
}

@Composable
fun StatCard(title: String, stats: Stats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Min: ${stats.min?.let { it.toString() + " RSD" } ?: "N/A"}")
                Text("Prosek: ${stats.avg?.let { it.toString() + " RSD" } ?: "N/A"}")
                Text("Max: ${stats.max?.let { it.toString() + " RSD" } ?: "N/A"}")
            }
        }
    }
}

fun computeStats(values: List<Int>): Stats {
    if (values.isEmpty()) return Stats(null, null, null)
    val min = values.minOrNull()
    val max = values.maxOrNull()
    val avg = values.average().toInt()
    return Stats(min, avg, max)
}

suspend fun scrapeAll(): List<PriceSample> {
    val list = mutableListOf<PriceSample>()
    // Aggregators (broad market ranges)
    list += safeScrape("SrediMe (šišanje)") { scrapeSrediMeHaircut() }
    list += safeScrape("SrediMe (pranje)") { scrapeSrediMeWash() }

    // Individual salons / booking pages (deterministic line items)
    list += safeScrape("Barbershop 1na1 (Setmore)") { scrapeSetmore1na1() }
    list += safeScrape("TopTrend") { scrapeTopTrend() }

    // Zakazite.rs salons
    list += safeScrape("Misterija (Zakazite)") { scrapeMisterijaZakazite() }
    list += safeScrape("Mister (Zakazite)") { scrapeZakaziteGeneric("https://zakazite.rs/mister", "Mister (Zakazite)") }
    list += safeScrape("Figaro Sistem (Zakazite)") { scrapeZakaziteGeneric("https://zakazite.rs/figaro-sistem", "Figaro Sistem (Zakazite)") }
    list += safeScrape("Šurda i sin (Zakazite)") { scrapeZakaziteGeneric("https://zakazite.rs/shurda-i-sin", "Šurda i sin (Zakazite)") }

    // SrediMe salon page (example: By Mistique, contains Muško šišanje)
    list += safeScrape("By Mistique (SrediMe salon)") { scrapeSrediMeSalon("https://www.sredime.rs/nis/salon-by-mistique", "By Mistique (SrediMe)") }

    return list
}


// 1) SrediMe.rs aggregate for muško šišanje in Niš (has min/max/avg on page)
suspend fun scrapeSrediMeHaircut(): List<PriceSample> {
    val url = "https://www.sredime.rs/nis/muski-frizeri/musko-sisanje"
    val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
    val text = doc.text()
    // Example phrase: "u rangu cena od 600 RSD do 1.200 RSD, sa prosečnom cenom od 760,71 RSD"
    val pattern = Pattern.compile(r"od\\s*(\\d[\\d\\.\\s]*)\\s*RSD\\s*do\\s*(\\d[\\d\\.\\s]*)\\s*RSD.*prosečnom cenom od\\s*(\\d[\\d\\.,\\s]*)\\s*RSD")
    val m = pattern.matcher(text)
    val result = mutableListOf<PriceSample>()
    if (m.find()) {
        val min = m.group(1).replace(".", "").replace(" ", "").toInt()
        val max = m.group(2).replace(".", "").replace(" ", "").toInt()
        val avg = m.group(3).replace(".", "").replace(" ", "").replace(",", "").toInt()
        result += PriceSample("SrediMe (šišanje)", "haircut", min)
        result += PriceSample("SrediMe (šišanje)", "haircut", max)
        result += PriceSample("SrediMe (šišanje)", "haircut", avg)
    }
    return result
}

// 2) SrediMe.rs for pranje kose
suspend fun scrapeSrediMeWash(): List<PriceSample> {
    val url = "https://www.sredime.rs/nis/svi-saloni/pranje-kose"
    val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
    val text = doc.text()
    // "u rangu cena od 100 RSD do 15.000 RSD, sa prosečnom cenom od 2.919,57 RSD"
    val pattern = Pattern.compile(r"od\\s*(\\d[\\d\\.\\s]*)\\s*RSD\\s*do\\s*(\\d[\\d\\.\\s]*)\\s*RSD.*prosečnom cenom od\\s*(\\d[\\d\\.,\\s]*)\\s*RSD")
    val m = pattern.matcher(text)
    val result = mutableListOf<PriceSample>()
    if (m.find()) {
        val min = m.group(1).replace(".", "").replace(" ", "").toInt()
        val max = m.group(2).replace(".", "").replace(" ", "").toInt()
        val avg = m.group(3).replace(".", "").replace(" ", "").replace(",", "").toInt()
        result += PriceSample("SrediMe (pranje)", "wash", min)
        result += PriceSample("SrediMe (pranje)", "wash", max)
        result += PriceSample("SrediMe (pranje)", "wash", avg)
    }
    return result
}

// 3) Setmore page for Barbershop 1 na 1 (Niš): Šišanje 900, Brada 900, Šišanje i brada 1700
suspend fun scrapeSetmore1na1(): List<PriceSample> {
    val url = "https://barbershop1na1booking.setmore.com/"
    val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
    val text = doc.text()
    val result = mutableListOf<PriceSample>()
    val haircut = Regex("Šišanje\\s*\\.\\s*Trajanje.*Cena:(\\d+)\\s*RSD").find(text)?.groupValues?.get(1)
    if (haircut != null) result += PriceSample("Barbershop 1na1 (Setmore)", "haircut", haircut.toInt())
    val beard = Regex("Brada\\s*\\.\\s*Trajanje.*Cena:(\\d+)\\s*RSD").find(text)?.groupValues?.get(1)
    if (beard != null) result += PriceSample("Barbershop 1na1 (Setmore)", "beard", beard.toInt())
    val combo = Regex("Šišanje i Brada\\s*\\.\\s*Trajanje.*Cena:(\\d+)\\s*RSD").find(text)?.groupValues?.get(1)
    if (combo != null && haircut != null) {
        // infer beard if missing: beard = combo - haircut (approx, not exact, but okay)
        val b = combo.toInt() - haircut.toInt()
        if (beard == null && b > 0) result += PriceSample("Barbershop 1na1 (Setmore, infer)", "beard", b)
    }
    return result
}

// 4) TopTrend salon page (explicit prices incl. pranje)
suspend fun scrapeTopTrend(): List<PriceSample> {
    val url = "https://www.toptrend.rs/muske-usluge.php"
    val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
    val text = doc.text()
    val res = mutableListOf<PriceSample>()
    Regex("Muško šišanje\\s*,?\\s*(\\d+)\\s*din").find(text)?.let {
        res += PriceSample("TopTrend", "haircut", it.groupValues[1].toInt())
    }
    Regex("Pranje kose\\s*,?\\s*(\\d+)\\s*din").find(text)?.let {
        res += PriceSample("TopTrend", "wash", it.groupValues[1].toInt())
    }
    return res
}

// 5) Zakazite.rs Misterija (very low budget salon). Has explicit line items.
suspend fun scrapeMisterijaZakazite(): List<PriceSample> {
    val url = "https://zakazite.rs/misterija"
    val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
    val text = doc.text()
    val res = mutableListOf<PriceSample>()
    Regex("Muško šišanje\\s*\\d+\\s*min\\.?\\s*(\\d+)[\\.,]?\\s*RSD").find(text)?.let {
        res += PriceSample("Misterija (Zakazite)", "haircut", it.groupValues[1].toInt())
    }
    Regex("Pranje kose\\s*\\d+\\s*min\\.?\\s*(\\d+)[\\.,]?\\s*RSD").find(text)?.let {
        res += PriceSample("Misterija (Zakazite)", "wash", it.groupValues[1].toInt())
    }
    return res
}
