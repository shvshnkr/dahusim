package fr.husi.subscription.catalog

import fr.husi.SubscriptionType
import fr.husi.group.SubscriptionFetchProfile
import java.net.URL

object SubscriptionCatalogParser {

    private const val HEADER = "HUSI_SUBSCRIPTION_CATALOG_V1"

    fun parse(raw: String): SubscriptionCatalogDocument {
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        require(lines.isNotEmpty()) { "catalog is empty" }
        require(lines.first() == HEADER) { "invalid header, expected $HEADER" }

        var generation: Long? = null
        var allowEmpty = false
        val entries = ArrayList<SubscriptionCatalogEntry>()
        val sourceIds = LinkedHashSet<String>()
        val upsertLinks = LinkedHashSet<String>()

        for (line in lines.drop(1)) {
            when {
                line.startsWith("generation=", ignoreCase = true) -> {
                    val value = line.substringAfter('=').trim()
                    generation = value.toLongOrNull() ?: error("invalid generation: $value")
                }

                line.startsWith("allow_empty=", ignoreCase = true) -> {
                    val value = line.substringAfter('=').trim().lowercase()
                    allowEmpty = value == "1" || value == "true" || value == "yes"
                }

                else -> {
                    val parts = line.split('|')
                    val action = parts.firstOrNull().orEmpty().uppercase()
                    when (action) {
                        "UPSERT" -> {
                            require(parts.size >= 6) {
                                "UPSERT requires 6+ fields: $line"
                            }
                            val sourceId = validateSourceId(parts[1])
                            require(sourceIds.add(sourceId)) { "duplicate source_id: $sourceId" }
                            val name = parts[2].ifBlank { "Subscription $sourceId" }
                            val link = parts[3].trim()
                            require(link.startsWith("https://")) {
                                "only https links are allowed for UPSERT: $sourceId"
                            }
                            require(upsertLinks.add(normalizeLink(link))) {
                                "duplicate UPSERT link: $link"
                            }
                            val subscriptionType = parseSubscriptionType(parts[4].trim())
                            val fetchProfile = parseFetchProfile(parts[5].trim())
                            val customUserAgent = parts.getOrNull(6).orEmpty().trim()
                            entries += SubscriptionCatalogEntry.Upsert(
                                sourceId = sourceId,
                                name = name,
                                link = link,
                                subscriptionType = subscriptionType,
                                fetchProfile = fetchProfile,
                                customUserAgent = customUserAgent,
                            )
                        }

                        "REMOVE" -> {
                            require(parts.size == 2) { "REMOVE requires 2 fields: $line" }
                            val sourceId = validateSourceId(parts[1])
                            require(sourceIds.add(sourceId)) { "duplicate source_id: $sourceId" }
                            entries += SubscriptionCatalogEntry.Remove(sourceId = sourceId)
                        }

                        else -> error("unknown record action: $action")
                    }
                }
            }
        }

        val resolvedGeneration = generation ?: error("missing generation")
        return SubscriptionCatalogDocument(
            generation = resolvedGeneration,
            allowEmpty = allowEmpty,
            entries = entries,
        )
    }

    private fun parseSubscriptionType(value: String): Int = when (value.uppercase()) {
        "RAW" -> SubscriptionType.RAW
        "OOCV1" -> SubscriptionType.OOCv1
        "SIP008" -> SubscriptionType.SIP008
        else -> error("unsupported subscription type: $value")
    }

    private fun parseFetchProfile(value: String): Int = when (value.lowercase()) {
        "default" -> SubscriptionFetchProfile.DEFAULT
        "happ" -> SubscriptionFetchProfile.HAPP
        "custom" -> SubscriptionFetchProfile.CUSTOM
        "v2rayng" -> SubscriptionFetchProfile.V2RAYNG
        "v2raytun" -> SubscriptionFetchProfile.V2RAYTUN
        "incy" -> SubscriptionFetchProfile.INCY
        else -> error("unsupported fetch profile: $value")
    }

    private fun validateSourceId(raw: String): String {
        val sourceId = raw.trim()
        require(sourceId.isNotBlank()) { "source_id is blank" }
        require(sourceId.matches(Regex("[a-zA-Z0-9._-]{3,80}"))) {
            "invalid source_id: $sourceId"
        }
        return sourceId
    }

    private fun normalizeLink(link: String): String {
        val trimmed = link.trim()
        return runCatching {
            val parsed = URL(trimmed)
            "${parsed.protocol.lowercase()}://${parsed.host.lowercase()}${parsed.file}"
        }.getOrElse {
            trimmed
        }
    }
}
