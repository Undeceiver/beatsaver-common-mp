package io.beatmaps.common

import com.maxmind.db.CHMCache
import com.maxmind.geoip2.DatabaseReader
import com.maxmind.geoip2.exception.GeoIp2Exception
import com.maxmind.geoip2.model.AbstractCountryResponse
import com.maxmind.geoip2.model.CityResponse
import io.beatmaps.common.amqp.hostname
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.origin
import io.ktor.server.request.userAgent
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.elastic.ElasticConfig
import io.micrometer.elastic.ElasticMeterRegistry
import io.micrometer.influx.InfluxConfig
import io.micrometer.influx.InfluxMeterRegistry
import nl.basjes.parse.useragent.UserAgentAnalyzer
import java.io.File
import java.net.InetAddress
import java.util.Timer
import java.util.TimerTask

val geodbFilePath = System.getenv("GEOIP_PATH") ?: "geolite2.mmdb"
val geoIp = File(geodbFilePath).let { file -> if (file.exists()) DatabaseReader.Builder(File(geodbFilePath)).withCache(CHMCache()).build() else null }
private val countryResponseAttr = AttributeKey<CountryInfo>("countryResponse")

data class CountryInfo(val success: Boolean, val countryCode: String, val continentCode: String, val state: String? = null) {
    constructor(cr: CityResponse) : this(cr, if (cr.country.isoCode == "US") cr.mostSpecificSubdivision.isoCode else null)
    constructor(cr: AbstractCountryResponse, state: String? = null) : this(cr.country.isoCode != null, cr.country.isoCode ?: "", cr.continent.code ?: "", state)
    constructor() : this(false, "", "")
}

fun ApplicationCall.getCountry(): CountryInfo {
    if (!attributes.contains(countryResponseAttr)) {
        if (geoIp == null) {
            CountryInfo()
        } else {
            try {
                CountryInfo(geoIp.city(InetAddress.getByName(request.origin.remoteHost)))
            } catch (e: GeoIp2Exception) {
                CountryInfo()
            }
        }.also {
            attributes.put(
                countryResponseAttr,
                it
            )
        }
    }

    return attributes[countryResponseAttr]
}

fun Application.installMetrics() {
    val esConfig: ElasticConfig = object : ElasticConfig {
        val config = mapOf(
            "host" to (System.getenv("ES_HOST") ?: "http://localhost:9200"),
            "userName" to (System.getenv("ES_USER") ?: "myusername"),
            "password" to (System.getenv("ES_PASS") ?: "token"),
            "step" to (System.getenv("ES_STEP") ?: "1m"),
            "batchSize" to "10000",
            "connectTimeout" to "5s",
            "readTimeout" to "10s",
            "indexDateFormat" to "yyyy-MM-dd"
        )

        override fun prefix() = "es"
        override fun get(k: String): String? = config[k.removePrefix("es.")]
    }

    val influxConfig: InfluxConfig = object : InfluxConfig {
        val config = mapOf(
            "org" to (System.getenv("INFLUX_ORG") ?: ""),
            "autoCreateDb" to "false",
            "batchSize" to "10000",
            "compressed" to "true",
            "connectTimeout" to "5s",
            "consistency" to "one",
            "db" to (System.getenv("INFLUX_DB") ?: "telegraf"),
            "enabled" to (System.getenv("INFLUX_ENABLED") ?: "false"),
            "password" to (System.getenv("INFLUX_PASS") ?: "mysecret"),
            "readTimeout" to "10s",
            "retentionPolicy" to (System.getenv("INFLUX_RP") ?: "two_weeks"),
            "step" to (System.getenv("INFLUX_STEP") ?: "1m"),
            "uri" to (System.getenv("INFLUX_URI") ?: "http://localhost:8086"),
            "userName" to (System.getenv("INFLUX_USER") ?: "myusername"),
            "token" to (System.getenv("INFLUX_TOKEN") ?: "token")
        )

        override fun prefix() = "influx"
        override fun get(k: String): String? = config[k.removePrefix("influx.")]
    }

    val uaa = UserAgentAnalyzer
        .newBuilder()
        .withCache(10_000)
        .withField("AgentClass")
        .withField("OperatingSystemNameVersionMajor")
        .withField("AgentName")
        .withField("AgentNameVersion")
        .withField("AgentNameVersionMajor")
        .addResources("classpath:agents/*.yaml")
        .build()

    val mods = hashSetOf(
        "BMBF", "QuestSongDownloader", "BeatSaverVoting", "ModAssistant", "Beatlist", "PlaylistManager", "MorePlaylists", "BSDataPuller",
        "DiTails", "BeatSaverUpdater", "BetterSongSearch", "Beatsaber", "BeatSaberPlus", "SongRequestManager", "PlaylistDownLoader", "Beatdrop",
        "SiraUtil", "BeatSyncConsole", "BeatSaverDownloader", "BS-Manager", "QuestPlaylistDownLoader"
    )

    // Request timing header
    intercept(ApplicationCallPipeline.Monitoring) {
        val t = Timings()
        t.begin("req")
        call.attributes.put(reqTime, t)

        val tags = call.request.userAgent()?.let {
            val parsed = uaa.parse(it)
            val agentMajorKey = if (mods.contains(parsed.get("AgentName").value)) "AgentNameVersion" else "AgentNameVersionMajor"

            mutableMapOf(
                "agentClass" to parsed.get("AgentClass").value,
                "agentMajor" to parsed.get(agentMajorKey).value,
                "osMajor" to parsed.get("OperatingSystemNameVersionMajor").value
            )
        } ?: mutableMapOf()

        call.attributes.put(extraTags, tags)
    }
    sendPipeline.intercept(ApplicationSendPipeline.After) {
        val mk = call.attributes[reqTime]
        mk.end("req")
        if (!context.response.isCommitted) context.response.header("Server-Timing", mk.getHeader())
    }

    val appMicrometerRegistry = if (System.getenv("INFLUX_ENABLED") != null) {
        InfluxMeterRegistry.builder(influxConfig).clock(Clock.SYSTEM).build()
    } else if (System.getenv("ES_ENABLED") != null) {
        ElasticMeterRegistry.builder(esConfig).clock(Clock.SYSTEM).build()
    } else {
        return
    }

    appMicrometerRegistry.config().commonTags("host", hostname ?: "unknown")

    install(MicrometerMetrics) {
        registry = appMicrometerRegistry
        distinctNotRegisteredRoutes = false
        distributionStatisticConfig = DistributionStatisticConfig.Builder().build()
        timers { call, _ ->
            call.attributes[extraTags].forEach {
                tag(it.key, it.value)
            }
            tag("address", call.request.local.let { "${it.serverHost}:${it.serverPort}" })
            tag("cn", call.getCountry().countryCode)
            call.getCountry().state?.let { state ->
                tag("state", state)
            }
        }
    }

    var meterExpiry = setOf<Meter>()
    Timer().scheduleAtFixedRate(
        object : TimerTask() {
            override fun run() {
                val unusedMeters = appMicrometerRegistry.meters.filter {
                    when (it) {
                        is Counter -> it.count() == 0.0
                        is io.micrometer.core.instrument.Timer -> it.count() == 0L
                        else -> false
                    }
                }

                val toRemove = meterExpiry.intersect(unusedMeters.toHashSet())
                meterExpiry = unusedMeters.minus(meterExpiry).toHashSet()

                toRemove.forEach {
                    appMicrometerRegistry.remove(it)
                }
            }
        },
        60000,
        60000
    )
}

private val extraTags = AttributeKey<MutableMap<String, String>>("extraTags")
private val reqTime = AttributeKey<Timings>("serverTiming")
suspend fun <T> ApplicationCall?.timeIt(name: String, block: suspend () -> T) = this?.attributes?.get(reqTime)?.timeIt(name, block) ?: block()
fun ApplicationCall.tag(name: String, value: String) = attributes.getOrNull(extraTags)?.put(name, value)

class Timings {
    private val metrics = mutableMapOf<String, Float>()
    private val begins = mutableMapOf<String, Long>()

    fun getHeader() = metrics.map { "${it.key};dur=${it.value}" }.joinToString(", ")

    fun begin(name: String) {
        begins[name] = System.nanoTime()
    }

    fun end(name: String) =
        add(name, ((System.nanoTime() - (begins[name] ?: 0)) / 1000) / 1000f)

    fun add(name: String, time: Float) {
        metrics[name] = time
    }

    suspend fun <T> timeIt(name: String, block: suspend () -> T) =
        begin(name).let {
            try {
                block()
            } finally {
                end(name)
            }
        }
}
