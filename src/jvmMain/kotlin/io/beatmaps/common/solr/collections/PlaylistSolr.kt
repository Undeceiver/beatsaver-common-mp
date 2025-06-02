package io.beatmaps.common.solr.collections

import io.beatmaps.common.SearchOrder
import io.beatmaps.common.solr.PercentageMinimumMatchExpression
import io.beatmaps.common.solr.SolrCollection
import io.beatmaps.common.solr.SolrHelper
import io.beatmaps.common.solr.SolrMs
import io.beatmaps.common.solr.SolrNow
import io.beatmaps.common.solr.SolrRecip
import io.beatmaps.common.solr.SolrScore
import io.beatmaps.common.solr.parsers.EDisMaxQuery
import org.apache.solr.client.solrj.SolrQuery

object PlaylistSolr : SolrCollection() {
    override val id = pint("id")
    val sId = string("sId")
    val ownerId = pint("ownerId")
    val verified = boolean("verified")
    val name = string("name")
    val description = string("description")
    val created = pdate("created")
    val deleted = pdate("deleted")
    val updated = pdate("updated")
    val songsChanged = pdate("songsChanged")
    val curated = pdate("curated")
    val curatorId = pint("curatorId")
    val minNps = pfloat("minNps")
    val maxNps = pfloat("maxNps")
    val totalMaps = pint("totalMaps")
    val type = string("type")
    val mapIds = pints("mapIds")

    // Copy fields
    val nameEn = string("name_en")
    val descriptionEn = string("description_en")

    // Weights
    private val queryFields = arrayOf(
        name to 4.0,
        nameEn to 1.0,
        descriptionEn to 0.5
    )

    private val boostFunction = SolrRecip(SolrMs(SolrNow, songsChanged), SolrHelper.MS_PER_YEAR, 1, 1)

    fun newQuery() =
        EDisMaxQuery()
            .setBoostFunction(boostFunction)
            .setQueryFields(*queryFields)
            .setTie(0.1)
            .setMinimumMatch(
                PercentageMinimumMatchExpression(-0.5f)
            )

    fun addSortArgs(q: SolrQuery, seed: Int?, searchOrder: SearchOrder, ascending: Boolean): SolrQuery =
        when (searchOrder) {
            SearchOrder.Relevance -> listOf(
                SolrScore.sort(ascending)
            )
            SearchOrder.Rating, SearchOrder.Duration, SearchOrder.Latest -> listOf(
                created.sort(ascending)
            )
            SearchOrder.Curated -> listOf(
                curated.sort(ascending),
                created.sort(ascending)
            )
            SearchOrder.Random -> listOf(
                SolrQuery.SortClause("random_$seed", if (ascending) SolrQuery.ORDER.asc else SolrQuery.ORDER.desc)
            )
        }.let {
            q.setSorts(it)
        }

    override val collection = System.getenv("SOLR_PLAYLIST_COLLECTION") ?: "playlists"
}
