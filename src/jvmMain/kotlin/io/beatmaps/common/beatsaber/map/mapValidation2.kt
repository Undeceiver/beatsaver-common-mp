package io.beatmaps.common.beatsaber.map

import io.beatmaps.common.beatsaber.BMValidator
import io.beatmaps.common.beatsaber.CutDirection
import io.beatmaps.common.beatsaber.correctType
import io.beatmaps.common.beatsaber.custom.CustomJsonEventV2
import io.beatmaps.common.beatsaber.exists
import io.beatmaps.common.beatsaber.isBetween
import io.beatmaps.common.beatsaber.isIn
import io.beatmaps.common.beatsaber.matches
import io.beatmaps.common.beatsaber.optionalNotNull
import io.beatmaps.common.beatsaber.validateEach
import io.beatmaps.common.beatsaber.validateForEach
import io.beatmaps.common.beatsaber.validateOptional
import io.beatmaps.common.beatsaber.validateWith
import io.beatmaps.common.beatsaber.vivify.Vivify.validateVivify
import io.beatmaps.common.zip.ExtractedInfo

fun BMValidator<BSDifficulty>.validate(info: ExtractedInfo, maxBeat: Float) {
    validate(BSDifficulty::version).correctType().exists().optionalNotNull().matches(Regex("\\d+\\.\\d+\\.\\d+"))
    validate(BSDifficulty::_notes).correctType().exists().optionalNotNull().validateForEach {
        validate(BSNote::_type).correctType().exists().isIn(0, 1, 3)
        validate(BSNote::_cutDirection).correctType().exists().optionalNotNull().validate(CutDirection) {
            it == null || it.validate { q ->
                q == null || (q in 0..8) || (q in 1000..1360)
            }
        }
        validate(BSNote::beat).correctType().exists().optionalNotNull().let {
            if (info.duration > 0) it.isBetween(0f, maxBeat)
        }
        validate(BSNote::_lineIndex).correctType().exists().optionalNotNull()
        validate(BSNote::_lineLayer).correctType().exists().optionalNotNull()
    }
    validate(BSDifficulty::_obstacles).correctType().exists().optionalNotNull().validateWith(::validateObstacle)
    validate(BSDifficulty::_events).correctType().exists().optionalNotNull().validateWith(::validateEvent)
    validate(BSDifficulty::_waypoints).correctType().optionalNotNull().validateWith(::validateWaypoint)
    validate(BSDifficulty::_specialEventsKeywordFilters).correctType().optionalNotNull().validateOptional {
        validate(BSSpecialEventKeywordFilters::_keywords).correctType().optionalNotNull().validateForEach {
            validate(BSSpecialEventsForKeyword::_keyword).correctType().exists().optionalNotNull()
            validate(BSSpecialEventsForKeyword::_specialEvents).correctType().exists().optionalNotNull().validateEach()
        }
    }
    validate(BSDifficulty::customData).correctType().optionalNotNull().validateOptional {
        validate(BSCustomDataV2::time).correctType().optionalNotNull()
        validate(BSCustomDataV2::_BPMChanges).correctType().optionalNotNull().validateWith(::validateBPMChange)
        validate(BSCustomDataV2::customEvents).correctType().optionalNotNull().validateForEach {
            info.vivifyAssets?.let { assets ->
                validate(CustomJsonEventV2::data).validateVivify(assets)
            }
        }
    }
    validate(BSDifficulty::_BPMChanges).correctType().optionalNotNull().validateWith(::validateBPMChange)
}

fun validateObstacle(validator: BMValidator<BSObstacle>) = validator.apply {
    validate(BSObstacle::_type).correctType().exists().optionalNotNull()
    validate(BSObstacle::_duration).correctType().exists().optionalNotNull()
    validate(BSObstacle::beat).correctType().exists().optionalNotNull()
    validate(BSObstacle::_lineIndex).correctType().exists().optionalNotNull()
    validate(BSObstacle::_width).correctType().exists().optionalNotNull()
}

fun validateEvent(validator: BMValidator<BSEvent>) = validator.apply {
    validate(BSEvent::beat).correctType().exists().optionalNotNull()
    validate(BSEvent::_type).correctType().exists().optionalNotNull()
    validate(BSEvent::_value).correctType().exists().optionalNotNull()
}

fun validateWaypoint(validator: BMValidator<BSWaypointV2>) = validator.apply {
    validate(BSWaypointV2::beat).correctType().exists().optionalNotNull()
    validate(BSWaypointV2::_lineIndex).correctType().exists().optionalNotNull()
    validate(BSWaypointV2::_lineLayer).correctType().exists().optionalNotNull()
    validate(BSWaypointV2::_offsetDirection).correctType().exists().optionalNotNull()
}

fun validateBPMChange(validator: BMValidator<BPMChange>) = validator.apply {
    validate(BPMChange::beat).exists().correctType().optionalNotNull()
    validate(BPMChange::_BPM).exists().correctType().optionalNotNull()
    validate(BPMChange::_beatsPerBar).correctType().optionalNotNull()
    validate(BPMChange::_metronomeOffset).correctType().optionalNotNull()
}
