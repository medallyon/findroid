package dev.jdtech.jellyfin.database

import androidx.room.TypeConverter
import dev.jdtech.jellyfin.models.DownloadItemType
import dev.jdtech.jellyfin.models.DownloadState
import dev.jdtech.jellyfin.models.FindroidChapter
import dev.jdtech.jellyfin.models.FindroidSegmentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.DateTime
import java.time.ZoneOffset
import java.util.UUID

class Converters {
    @TypeConverter
    fun fromStringToUUID(value: String?): UUID? {
        return value?.let { UUID.fromString(it) }
    }

    @TypeConverter
    fun fromUUIDToString(value: UUID?): String? {
        return value?.toString()
    }

    @TypeConverter
    fun fromDateTimeToLong(value: DateTime?): Long? {
        return value?.toEpochSecond(ZoneOffset.UTC)
    }

    @TypeConverter
    fun fromLongToDatetime(value: Long?): DateTime? {
        return value?.let { DateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }
    }

    @TypeConverter
    fun fromFindroidChaptersToString(value: List<FindroidChapter>?): String? {
        return value?.let { Json.encodeToString(value) }
    }

    @TypeConverter
    fun fromStringToFindroidChapters(value: String?): List<FindroidChapter>? {
        return value?.let { Json.decodeFromString(value) }
    }

    @TypeConverter
    fun fromFindroidSegmentTypeToString(value: FindroidSegmentType): String {
        return value.name
    }

    @TypeConverter
    fun fromStringToFindroidSegmentType(value: String): FindroidSegmentType {
        return FindroidSegmentType.valueOf(value)
    }

    @TypeConverter
    fun fromDownloadStateToString(value: DownloadState): String {
        return value.name
    }

    @TypeConverter
    fun fromStringToDownloadState(value: String): DownloadState {
        return DownloadState.valueOf(value)
    }

    @TypeConverter
    fun fromDownloadItemTypeToString(value: DownloadItemType): String {
        return value.name
    }

    @TypeConverter
    fun fromStringToDownloadItemType(value: String): DownloadItemType {
        return DownloadItemType.valueOf(value)
    }
}
