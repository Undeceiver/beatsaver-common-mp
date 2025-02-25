package io.beatmaps.common

import io.ktor.resources.ResourceSerializationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

val json = Json {
    prettyPrint = true
}

val jsonWithDefaults = Json(json) {
    encodeDefaults = true
}

val jsonIgnoreUnknown = Json(json) {
    ignoreUnknownKeys = true
    prettyPrintIndent = "  "
}

sealed class OptionalProperty<out T> {
    object WrongType : OptionalProperty<Nothing>() {
        override fun validate(notPresent: Boolean, block: (Nothing) -> Boolean) = notPresent
        override fun orNull() = null
    }
    object NotPresent : OptionalProperty<Nothing>() {
        override fun validate(notPresent: Boolean, block: (Nothing) -> Boolean) = notPresent
        override fun orNull() = null
    }
    data class Present<T>(val value: T) : OptionalProperty<T>() {
        override fun validate(notPresent: Boolean, block: (T) -> Boolean) = block(value)
        override fun orNull(): T? = value
    }

    abstract fun validate(notPresent: Boolean = true, block: (T) -> Boolean): Boolean
    abstract fun orNull(): T?
}

fun <T : Any> OptionalProperty<T?>?.or(v: T) = this?.orNull() ?: v

@Suppress("UNCHECKED_CAST")
open class OptionalPropertySerializer<T>(
    private val valueSerializer: KSerializer<T>
) : KSerializer<OptionalProperty<T>> {
    final override val descriptor: SerialDescriptor = valueSerializer.descriptor

    final override fun deserialize(decoder: Decoder): OptionalProperty<T> =
        try {
            val original = valueSerializer.deserialize(decoder)
            val final = if (original is AdditionalProperties) {
                AdditionalPropertiesTransformer(original.properties, valueSerializer as KSerializer<AdditionalProperties>)
                    .deserialize(decoder) as T
            } else {
                original
            }

            OptionalProperty.Present(final)
        } catch (e: ResourceSerializationException) {
            OptionalProperty.WrongType
        } catch (e: IllegalArgumentException) {
            OptionalProperty.WrongType
        }

    final override fun serialize(encoder: Encoder, value: OptionalProperty<T>) {
        when (value) {
            OptionalProperty.WrongType, OptionalProperty.NotPresent -> throw SerializationException(
                "Tried to serialize an optional property that had no value present. Is encodeDefaults false?"
            )
            is OptionalProperty.Present ->
                if (value.value is AdditionalProperties) {
                    AdditionalPropertiesTransformer(value.value.properties, valueSerializer as KSerializer<AdditionalProperties>)
                        .serialize(encoder, value.value)
                } else {
                    valueSerializer.serialize(encoder, value.value)
                }
        }
    }
}

interface AdditionalProperties {
    val additionalInformation: Map<String, JsonElement>
    val properties: Set<String>
}

open class AdditionalPropertiesTransformer<T : AdditionalProperties>(
    private val properties: Set<String>,
    valueSerializer: KSerializer<T>
) : JsonTransformingSerializer<T>(valueSerializer) {
    override fun transformDeserialize(element: JsonElement) =
        if (element is JsonObject) {
            val additionalInformation = JsonObject(element.minus(properties))
            JsonObject(element.plus(KEY to additionalInformation))
        } else {
            element
        }

    override fun transformSerialize(element: JsonElement) =
        ((element as? JsonObject)?.get(KEY) as? JsonObject)?.let { additionalInformation ->
            JsonObject(element.minus(KEY).plus(additionalInformation.toList().toTypedArray()))
        } ?: element

    companion object {
        const val KEY = "additionalInformation"
    }
}
