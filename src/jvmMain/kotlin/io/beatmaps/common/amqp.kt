package io.beatmaps.common

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.DeliverCallback
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import pl.jutupe.ktor_rabbitmq.RabbitMQ
import pl.jutupe.ktor_rabbitmq.RabbitMQConfiguration
import pl.jutupe.ktor_rabbitmq.RabbitMQInstance
import pl.jutupe.ktor_rabbitmq.publish
import pl.jutupe.ktor_rabbitmq.rabbitConsumer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger
import kotlin.reflect.KClass

val es: ExecutorService = Executors.newFixedThreadPool(4)

val hostname: String = System.getenv("HOSTNAME") ?: ""
val rabbitHost: String = System.getenv("RABBITMQ_HOST") ?: ""
val rabbitPort: String = System.getenv("RABBITMQ_PORT") ?: "5672"
val rabbitUser: String = System.getenv("RABBITMQ_USER") ?: "guest"
val rabbitPass: String = System.getenv("RABBITMQ_PASS") ?: "guest"
val rabbitVhost: String = System.getenv("RABBITMQ_VHOST") ?: ""
private val rabbitLogger = Logger.getLogger("bmio.RabbitMQ")
val genericQueueConfig = mapOf("x-dead-letter-exchange" to "beatmaps.dlq")

fun RabbitMQConfiguration.setupAMQP(block: Channel.() -> Unit = {}) = apply {
    uri = "amqp://$rabbitUser:$rabbitPass@$rabbitHost:$rabbitPort/$rabbitVhost"
    connectionName = hostname

    serialize { jackson.writeValueAsBytes(it) }
    deserialize { bytes, type -> jackson.readValue(bytes, type.javaObjectType) }

    initialize(block)
}

fun Application.rabbitOptional(configuration: RabbitMQInstance.() -> Unit) {
    if (rabbitHost.isNotEmpty()) {
        rabbitConsumer(configuration)
    } else {
        rabbitLogger.warning("RabbitMQ not set up")
    }
}

fun Application.rb() = if (rabbitHost.isNotEmpty()) { attributes[RabbitMQ.RabbitMQKey] } else null
fun ApplicationCall.rb() = application.rb()

fun <T> Application.pub(exchange: String, routingKey: String, props: AMQP.BasicProperties?, body: T) =
    rb()?.publish(exchange, routingKey, props, body)

fun <T> ApplicationCall.pub(exchange: String, routingKey: String, props: AMQP.BasicProperties?, body: T) =
    application.pub(exchange, routingKey, props, body)

private fun RabbitMQInstance.getConnection() =
    javaClass.getDeclaredField("connection").let {
        it.isAccessible = true
        it.get(this) as Connection
    }

fun <T : Any> RabbitMQInstance.consumeAck(
    queue: String,
    clazz: KClass<T>,
    rabbitDeliverCallback: suspend (routingKey: String, body: T) -> Unit
) {
    val logger = Logger.getLogger("bmio.RabbitMQ.consumeAck")
    getConnection().createChannel().apply {
        basicQos(20)
        basicConsume(
            queue,
            false,
            DeliverCallback { _, message ->
                runBlocking(es.asCoroutineDispatcher()) {
                    runCatching {
                        val mappedEntity = jackson.readValue(message.body, clazz.javaObjectType)

                        rabbitDeliverCallback.invoke(message.envelope.routingKey, mappedEntity)

                        basicAck(message.envelope.deliveryTag, false)
                    }.getOrElse {
                        logger.warning("Or else: ${it.message}")

                        basicNack(message.envelope.deliveryTag, false, false)
                    }
                }
            },
            CancelCallback {
                logger.warning("Consume cancelled: $it")
            }
        )
    }
}
