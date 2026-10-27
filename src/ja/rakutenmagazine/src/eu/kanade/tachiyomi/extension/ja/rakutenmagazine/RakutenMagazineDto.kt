package eu.kanade.tachiyomi.extension.ja.rakutenmagazine

import kotlinx.serialization.Serializable

@Serializable
data class LatestIssuesResponse(
    val latestIssues: List<IssueDto>? = emptyList(),
)

@Serializable
data class NoticeTitlesResponse(
    val noticeTitles: List<IssueDto>? = emptyList(),
)

@Serializable
data class RelatedIssuesResponse(
    val backNumber: List<IssueDto>? = emptyList(),
    val relatedTitle: List<IssueDto>? = emptyList(),
)

@Serializable
data class UserStatusResponse(
    val message: String? = "",
    val userInfo: UserInfoDto? = null,
)

@Serializable
data class UserInfoDto(
    val status: Int = -1,
    val planType: String? = "",
    val iapStatus: String? = "",
)

@Serializable
data class IssueDto(
    val rank: Int = 10000,
    val categoryId: String? = "",
    val categoryName: String? = "",
    val titleIdentifier: String? = "",
    val titleName: String? = "",
    val issueId: String? = "",
    val issueName: String? = "",
    val thumbnail: String? = "",
    val imageUrl: String? = "",
    val description: String? = "",
    val publicationDate: String? = "",
    val endDate: String? = "",
    val previewUrlExist: Boolean = false,
    val contentUrlExist: Boolean = false,
)

@Serializable
data class AquafadasDocumentInfo(
    val version: String? = "",
    val document: AquafadasDocMeta? = null,
    val readers: List<AquafadasReaderEntry>? = emptyList(),
)

@Serializable
data class AquafadasDocMeta(
    val title: String? = "",
    val collectionTitle: String? = "",
    val cover: String? = "",
)

@Serializable
data class AquafadasReaderEntry(
    val id: String? = "",
    val type: String? = "",
    val pageCount: Int = 0,
    val fileName: String? = "AVED0.proj",
)

@Serializable
data class AquafadasAvedProj(
    val version: String? = "",
    val pages: List<AquafadasSpread>? = emptyList(),
)

@Serializable
data class AquafadasSpread(
    val pageId: Int = 0,
    val pages: List<AquafadasPage>? = emptyList(),
)

@Serializable
data class AquafadasPage(
    val pageId: Int = 0,
    val id: Int = 0,
    val name: String? = "",
    val width: String? = "",
    val height: String? = "",
    val fileName: String? = "",
    val preview: String? = "",
    val thumbnail: String? = "",
)
